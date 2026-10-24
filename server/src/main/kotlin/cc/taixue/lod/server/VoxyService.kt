package cc.taixue.lod.server

import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.core.ColumnValidation
import cc.taixue.lod.core.LodLimits
import cc.taixue.lod.core.SessionGate
import cc.taixue.lod.core.Wire
import dev.vox.lss.common.wire.WireSectionCursor
import java.io.IOException
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** LSS v20 Paper adapter: bounded work, persistent terrain and authorization before every send. */
class VoxyService(private val plugin: JavaPlugin, private val access: WorldAccess) :
    PluginMessageListener, Listener, AutoCloseable {
    private class Peer(val player: UUID, val capabilities: Int) {
        var lease: SessionGate.Lease? = null
        val wants = linkedMapOf<Long, Long>()
        var receivedTick = 0L
        var received = 0
        var configuredRadius = 0
    }

    private val peers = linkedMapOf<UUID, Peer>()
    private val revisions = mutableMapOf<ColumnCache.Key, Long>()
    private val busy = mutableSetOf<ColumnCache.Key>()
    private val workers =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(128),
            { task -> Thread(task, "ArchLOD-Voxy").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val active = AtomicInteger()
    private val cache: ColumnCache
    private val raw: RawSender
    private var tick = 0L
    private var errors = 0L
    private var uploads: UploadService? = null
    private var closed = false

    init {
        try {
            raw = RawSender()
            cache =
                ColumnCache(
                    plugin.dataFolder.toPath().resolve("voxy-cache"),
                    Duration.ofSeconds(
                        plugin.config
                            .getInt("lod.cache-max-age-seconds", -1)
                            .coerceIn(-1, 3600)
                            .toLong()
                    ),
                    -1,
                )
        } catch (error: IOException) {
            throw IllegalStateException("Cannot initialize Voxy transport/cache", error)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Cannot initialize Voxy transport/cache", error)
        }
    }

    fun start() {
        val messenger = plugin.server.messenger
        listOf(HANDSHAKE, REQUEST, "lss:client_info", "lss:region_summary_req").forEach {
            messenger.registerIncomingPluginChannel(plugin, it, this)
        }
        listOf(CONFIG, COLUMN, RESPONSE, DIRTY).forEach {
            messenger.registerOutgoingPluginChannel(plugin, it)
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), 1, 1)
        uploads = UploadService(plugin, access, raw, this)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (closed || message.size > 32768) return
        try {
            val reader = Wire.Reader(message)
            if (channel == HANDSHAKE) {
                val protocol = reader.varInt()
                val capabilities = if (reader.remaining() > 0) reader.varInt() else 0
                reader.end()
                if (protocol != 20) return
                val old = peers[player.uniqueId]
                if (old != null && tick - old.receivedTick < 20) return
                val peer = Peer(player.uniqueId, capabilities).apply { receivedTick = tick }
                if (capabilities and 1 == 0) {
                    config(player, peer, false)
                    return
                }
                peer.lease = access.issue(player.uniqueId, player.world.uid)
                peers[player.uniqueId] = peer
                config(player, peer, peer.lease != null)
                return
            }
            if (channel != REQUEST) return
            val peer = peers[player.uniqueId] ?: return
            if (!access.valid(peer.lease)) return
            if (peer.receivedTick != tick) {
                peer.receivedTick = tick
                peer.received = 0
            }
            if (++peer.received > 8) return
            val count = reader.count(1024)
            val maximum = plugin.config.getInt("lod.max-queued-per-player", 256).coerceIn(16, 1024)
            repeat(count) {
                val position = reader.i64()
                val timestamp = reader.i64()
                if (
                    peer.wants.size < maximum &&
                        access.inRange(
                            peer.lease,
                            Wire.x(position).toLong(),
                            Wire.z(position).toLong(),
                        )
                ) {
                    peer.wants.putIfAbsent(position, timestamp)
                }
            }
            reader.end()
        } catch (_: IllegalArgumentException) {
            // Malformed clients receive no data and cannot allocate unbounded work.
        }
    }

    private fun config(player: Player, peer: Peer, enabled: Boolean) {
        val output =
            Wire.Writer()
                .varInt(20)
                .bool(enabled)
                .varInt(radius())
                .bool(false)
                .varInt(Bukkit.getUnsafe().dataVersion)
        if (peer.capabilities and 8 != 0) output.varInt(0)
        peer.configuredRadius = radius()
        raw.send(player, CONFIG, output.result())
    }

    private fun radius() =
        LodLimits.radius(plugin.config.getInt("lod.radius-chunks", LodLimits.DEFAULT_RADIUS_CHUNKS))

    private fun revision(key: ColumnCache.Key): Long = revisions[key] ?: 0L

    private fun inRange(lease: SessionGate.Lease?, key: ColumnCache.Key) =
        access.inRange(lease, key.x.toLong(), key.z.toLong())

    private fun tick() {
        if (closed) return
        tick++
        var budget = plugin.config.getInt("lod.columns-per-tick", 2).coerceIn(1, 8)
        val ordered = peers.values.toMutableList()
        if (ordered.isNotEmpty()) Collections.rotate(ordered, -(tick % ordered.size).toInt())
        for (peer in ordered) {
            val player = Bukkit.getPlayer(peer.player)
            if (player == null || !player.isOnline) {
                peers.remove(peer.player)
                continue
            }
            val current = access.issue(peer.player, player.world.uid)
            if (peer.lease != current || peer.configuredRadius != radius()) {
                peer.lease = current
                peer.wants.clear()
                config(player, peer, current != null)
            }
            if (current == null || budget <= 0 || active.get() >= 4 || peer.wants.isEmpty())
                continue
            val want = peer.wants.entries.first()
            val position = want.key
            val timestamp = want.value
            peer.wants.remove(position)
            val key = ColumnCache.Key(current.world, Wire.x(position), Wire.z(position))
            if (!inRange(current, key) || key in busy) continue
            busy += key
            active.incrementAndGet()
            budget--
            val revision = revision(key)
            try {
                workers.execute {
                    val hit = cache.get(key)
                    main {
                        if (hit == null) load(current, key, revision, timestamp)
                        else deliver(current, key, revision, timestamp, hit)
                    }
                }
            } catch (_: RejectedExecutionException) {
                finish(key)
            }
        }
    }

    private fun load(
        lease: SessionGate.Lease,
        key: ColumnCache.Key,
        revision: Long,
        clientStamp: Long,
    ) {
        if (!inRange(lease, key) || revision != revision(key)) {
            finish(key)
            return
        }
        val world = Bukkit.getWorld(key.world)
        if (world == null) {
            finish(key)
            return
        }
        if (!world.isChunkGenerated(key.x, key.z)) {
            answer(lease, key, 2)
            finish(key)
            return
        }
        // gen=false is essential: remote render requests must never create terrain.
        if (!world.isChunkLoaded(key.x, key.z)) BackgroundChunks.loaded(world.uid, key.x, key.z)
        world.getChunkAtAsync(key.x, key.z, false).whenComplete { chunk, error ->
            main {
                if (error != null || chunk == null) {
                    answer(lease, key, 2)
                    finish(key)
                    return@main
                }
                if (!inRange(lease, key) || revision != revision(key)) {
                    finish(key)
                    return@main
                }
                val snapshot = chunk.getChunkSnapshot(true, true, false)
                val minimum = world.minHeight
                val maximum = world.maxHeight
                try {
                    workers.execute {
                        try {
                            val entry =
                                ColumnCache.Entry(
                                    System.currentTimeMillis(),
                                    ColumnEncoder.encode(snapshot, minimum, maximum),
                                )
                            main deliver@{
                                if (revision != revision(key)) {
                                    finish(key)
                                    return@deliver
                                }
                                try {
                                    workers.execute {
                                        try {
                                            if (access.cacheAllowed(key.world))
                                                cache.put(key, entry)
                                        } catch (error: IOException) {
                                            warn(error)
                                        }
                                    }
                                } catch (_: RejectedExecutionException) {
                                    // The live response remains usable when the cache write queue
                                    // is full.
                                }
                                deliver(lease, key, revision, clientStamp, entry)
                            }
                        } catch (error: RuntimeException) {
                            warn(error)
                            main { finish(key) }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    finish(key)
                }
            }
        }
    }

    private fun deliver(
        lease: SessionGate.Lease,
        key: ColumnCache.Key,
        revision: Long,
        stamp: Long,
        entry: ColumnCache.Entry,
    ) {
        try {
            if (revision != revision(key) || !inRange(lease, key)) return
            if (stamp > 0 && stamp >= entry.timestamp) {
                answer(lease, key, 1)
                return
            }
            val world = Bukkit.getWorld(key.world) ?: return
            val frame =
                Wire.Writer()
                    .i32(key.x)
                    .i32(key.z)
                    .utf(world.key.toString())
                    .i64(entry.timestamp)
                    .u8(3)
                    .u8(0)
                    .bytes(entry.body)
                    .result()
            access.send(lease) {
                Bukkit.getPlayer(lease.player)?.let { raw.send(it, COLUMN, frame) }
            }
        } catch (error: RuntimeException) {
            warn(error)
        } finally {
            finish(key)
        }
    }

    private fun answer(lease: SessionGate.Lease, key: ColumnCache.Key, response: Int) {
        val frame = Wire.Writer().varInt(1).u8(response).i64(Wire.pack(key.x, key.z)).result()
        access.send(lease) { Bukkit.getPlayer(lease.player)?.let { raw.send(it, RESPONSE, frame) } }
    }

    private fun finish(key: ColumnCache.Key) {
        if (busy.remove(key)) active.decrementAndGet()
    }

    private fun main(operation: () -> Unit) {
        if (!closed && plugin.isEnabled)
            Bukkit.getScheduler().runTask(plugin, Runnable { if (!closed) operation() })
    }

    @Synchronized
    private fun warn(error: Exception) {
        if (++errors == 1L || errors % 100 == 0L)
            plugin.logger.warning("Voxy cache/transport: $error ($errors)")
    }

    fun dirty(world: World, x: Int, z: Int) {
        BackgroundChunks.changed(world.uid, x, z)
        val key = ColumnCache.Key(world.uid, x, z)
        revisions.merge(key, 1L, Long::plus)
        try {
            workers.execute {
                try {
                    cache.invalidate(key)
                } catch (error: IOException) {
                    warn(error)
                }
            }
        } catch (_: RejectedExecutionException) {
            // The revision still prevents stale queued responses from being delivered.
        }
        val message = Wire.Writer().varInt(1).i64(Wire.pack(x, z)).result()
        for (peer in peers.values) {
            val lease = peer.lease ?: continue
            if (lease.world == world.uid && inRange(lease, key)) {
                access.send(lease) {
                    Bukkit.getPlayer(peer.player)?.let { raw.send(it, DIRTY, message) }
                }
            }
        }
    }

    private fun dirty(block: Block) = dirty(block.world, block.x shr 4, block.z shr 4)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun place(event: BlockPlaceEvent) = dirty(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) = dirty(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun explode(event: BlockExplodeEvent) = event.blockList().forEach(::dirty)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun piston(event: BlockPistonExtendEvent) =
        event.blocks.forEach {
            dirty(it)
            dirty(it.getRelative(event.direction))
        }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun retract(event: BlockPistonRetractEvent) =
        event.blocks.forEach {
            dirty(it)
            dirty(it.getRelative(event.direction))
        }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun bucket(event: PlayerBucketEmptyEvent) = dirty(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun fill(event: PlayerBucketFillEvent) = dirty(event.block)

    @EventHandler(priority = EventPriority.MONITOR)
    fun unload(event: WorldUnloadEvent) {
        peers.values.filter { it.lease?.world == event.world.uid }.forEach { it.wants.clear() }
    }

    fun wantsUpload(key: ColumnCache.Key, claimed: Long, result: Consumer<Boolean>) {
        try {
            workers.execute {
                val hit = cache.get(key)
                main {
                    result.accept(
                        hit == null ||
                            (claimed > hit.timestamp &&
                                plugin.config.getBoolean("upload.trusted-client-timestamps", false))
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            result.accept(false)
        }
    }

    fun mergeUpload(
        lease: SessionGate.Lease,
        key: ColumnCache.Key,
        uploaded: WireSectionCursor.WireColumn,
        result: Consumer<Boolean>,
    ) {
        if (!inRange(lease, key)) {
            result.accept(false)
            return
        }
        val world = Bukkit.getWorld(key.world)
        if (world == null || !world.isChunkGenerated(key.x, key.z)) {
            result.accept(false)
            return
        }
        val revision = revision(key)
        if (!world.isChunkLoaded(key.x, key.z)) BackgroundChunks.loaded(world.uid, key.x, key.z)
        world.getChunkAtAsync(key.x, key.z, false).whenComplete { chunk, error ->
            main {
                if (error != null || chunk == null || !inRange(lease, key)) {
                    result.accept(false)
                    return@main
                }
                val snapshot = chunk.getChunkSnapshot(true, true, false)
                val minimum = world.minHeight
                val maximum = world.maxHeight
                try {
                    workers.execute {
                        try {
                            // Missing client sections mean unknown, never air. Saved terrain fills
                            // gaps and validates the offer.
                            val canonical = ColumnEncoder.encode(snapshot, minimum, maximum)
                            val actual =
                                ColumnValidation.validate(canonical, minimum / 16, maximum / 16)
                            if (!matchingSections(uploaded, actual)) {
                                main { result.accept(false) }
                                return@execute
                            }
                            main merge@{
                                if (!access.valid(lease) || revision != revision(key)) {
                                    result.accept(false)
                                    return@merge
                                }
                                val entry = ColumnCache.Entry(System.currentTimeMillis(), canonical)
                                try {
                                    workers.execute {
                                        try {
                                            if (access.cacheAllowed(key.world))
                                                cache.put(key, entry)
                                            main { result.accept(true) }
                                        } catch (error: IOException) {
                                            warn(error)
                                            main { result.accept(false) }
                                        }
                                    }
                                } catch (_: RejectedExecutionException) {
                                    result.accept(false)
                                }
                            }
                        } catch (_: RuntimeException) {
                            main { result.accept(false) }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    result.accept(false)
                }
            }
        }
    }

    private fun matchingSections(
        uploaded: WireSectionCursor.WireColumn,
        actual: WireSectionCursor.WireColumn,
    ): Boolean {
        if (uploaded.sections().isEmpty()) return false
        for (section in uploaded.sections()) {
            val authoritative =
                actual.sections().firstOrNull { it.sectionY() == section.sectionY() }
                    ?: return false
            val offered = ColumnValidation.values(section.blocks(), 4096)
            val known = ColumnValidation.values(authoritative.blocks(), 4096)
            for (index in offered.indices) if (
                uploaded.dictionary()[offered[index]] != actual.dictionary()[known[index]]
            )
                return false
        }
        return true
    }

    fun cache(): ColumnCache = cache

    fun refreshSnapshot(world: World, snapshot: ChunkSnapshot): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val key = ColumnCache.Key(world.uid, snapshot.x, snapshot.z)
        if (!access.cacheAllowed(key.world)) {
            result.complete(false)
            return result
        }
        val revision = revision(key)
        val minimum = world.minHeight
        val maximum = world.maxHeight
        try {
            workers.execute {
                try {
                    val entry =
                        ColumnCache.Entry(
                            System.currentTimeMillis(),
                            ColumnEncoder.encode(snapshot, minimum, maximum),
                        )
                    main {
                        if (!access.cacheAllowed(key.world) || revision != revision(key)) {
                            result.complete(false)
                            return@main
                        }
                        try {
                            workers.execute {
                                try {
                                    if (access.cacheAllowed(key.world)) {
                                        cache.put(key, entry)
                                        result.complete(true)
                                    } else result.complete(false)
                                } catch (error: Exception) {
                                    result.completeExceptionally(error)
                                }
                            }
                        } catch (error: RejectedExecutionException) {
                            result.completeExceptionally(error)
                        }
                    }
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            result.completeExceptionally(error)
        }
        return result
    }

    fun warm(world: World, x: Int, z: Int): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        if (!access.cacheAllowed(world.uid) || !world.isChunkGenerated(x, z)) {
            result.complete(false)
            return result
        }
        if (!world.isChunkLoaded(x, z)) BackgroundChunks.loaded(world.uid, x, z)
        world.getChunkAtAsync(x, z, false).whenComplete { chunk, error ->
            main {
                if (error != null) {
                    result.completeExceptionally(error)
                    return@main
                }
                if (chunk == null) {
                    result.complete(false)
                    return@main
                }
                refreshSnapshot(world, chunk.getChunkSnapshot(true, true, false)).whenComplete {
                    ok,
                    failure ->
                    if (failure != null) result.completeExceptionally(failure)
                    else result.complete(ok)
                }
            }
        }
        return result
    }

    fun uploads(): UploadService = checkNotNull(uploads)

    override fun close() {
        uploads?.close()
        closed = true
        peers.clear()
        workers.shutdownNow()
        HandlerList.unregisterAll(this)
    }

    private companion object {
        const val HANDSHAKE = "lss:handshake_c2s"
        const val REQUEST = "lss:batch_chunk_req"
        const val CONFIG = "lss:session_config"
        const val COLUMN = "lss:voxel_column"
        const val RESPONSE = "lss:batch_response"
        const val DIRTY = "lss:dirty_columns"
    }
}
