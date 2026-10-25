package cc.taixue.lod.server

import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.core.ColumnIndex
import cc.taixue.lod.core.FastTerrain
import cc.taixue.lod.core.LodLimits
import cc.taixue.lod.core.NativeRegionStore
import cc.taixue.lod.core.PreparedColumn
import cc.taixue.lod.core.SessionGate
import cc.taixue.lod.core.TerrainBatch
import cc.taixue.lod.core.Wire
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener

/** Cached-only native synchronization. World leases guard indexes as well as every data frame. */
class FastTerrainService(
    private val plugin: LodServer,
    private val access: WorldAccess,
    private val raw: RawSender,
    private val source: ColumnCache,
    private val regenerate: (UUID, Long) -> Unit,
) : PluginMessageListener, AutoCloseable {
    private class Peer(
        val player: UUID,
        val token: Long,
        val radius: Int,
        var lease: SessionGate.Lease?,
    ) {
        val wants = linkedMapOf<Long, Long>()
        var index: List<Pair<ColumnCache.Key, Long>> = emptyList()
        var cursor = 0
        var scanning = false
        var lastScan = -100L
        var indexReady = false
        var doneSent = false
        var reading = false
        var packets = 0
        var packetTick = 0L
    }

    private val serverIdentity = run {
        val file = plugin.dataFolder.toPath().resolve("native-server-id.txt")
        if (java.nio.file.Files.exists(file))
            UUID.fromString(java.nio.file.Files.readString(file).trim()).toString()
        else
            UUID.randomUUID().toString().also {
                java.nio.file.Files.writeString(
                    file,
                    it,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                )
            }
    }
    private val peers = linkedMapOf<UUID, Peer>()
    private val catalog = ColumnIndex()
    val regions =
        NativeRegionStore(plugin.dataFolder.toPath().resolve("voxy-native-v1")).also {
            it.quota = plugin.cacheQuota
        }
    private val opacity = NativeOpacity()
    private val cacheMemoryListener: (Boolean) -> Unit = { regions.setMemoryCaching(it) }
    private val workers =
        ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(8),
            { task -> Thread(task, "LOD-native-cache").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private var ticks = 0L
    @Volatile private var closed = false
    @Volatile private var indexed = false
    private var jobs = 0
    private val warming = mutableMapOf<UUID, Int>()

    fun start() {
        // Fail visibly instead of silently preparing a different opacity model.
        check(opacity.of("minecraft:stone") == 15) { "Paper opacity mapping unavailable" }
        regions.setMemoryCaching(plugin.backgroundLoad.duty > 0)
        plugin.backgroundLoad.stateListeners.add(cacheMemoryListener)
        source.changed = { key, version -> catalog[key] = version }
        workers.execute {
            val slice = plugin.backgroundLoad.reader { closed }
            source.forEachCached(checkpoint = { slice.checkpoint() }) { key, version ->
                if (version > 0) catalog.putIfAbsent(key, version)
            }
            indexed = true
        }
        listOf(FastTerrain.HELLO, FastTerrain.REQUEST).forEach {
            plugin.server.messenger.registerIncomingPluginChannel(plugin, it, this)
        }
        listOf(FastTerrain.CONFIG, FastTerrain.INDEX, FastTerrain.DATA).forEach {
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, it)
        }
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), 1, 1)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (closed || message.size > 32768) return
        try {
            val reader = Wire.Reader(message)
            require(reader.varInt() == 1)
            val token = reader.i64()
            if (channel == FastTerrain.HELLO) {
                val radius = LodLimits.radius(reader.varInt())
                reader.end()
                val previous = peers[player.uniqueId]
                if (previous != null && ticks - previous.packetTick < 20) return
                val peer =
                    Peer(
                        player.uniqueId,
                        token,
                        radius,
                        access.issue(player.uniqueId, player.world.uid),
                    )
                peer.packetTick = ticks
                peers[player.uniqueId] = peer
                config(peer)
                return
            }
            val peer = peers[player.uniqueId] ?: return
            if (peer.token != token || !access.valid(peer.lease)) return
            if (peer.packetTick != ticks) {
                peer.packetTick = ticks
                peer.packets = 0
            }
            if (++peer.packets > 8) return
            val items = mutableListOf<Pair<Long, Long>>()
            repeat(reader.count(FastTerrain.REQUEST_BATCH)) {
                items += reader.i64() to reader.i64()
            }
            reader.end()
            for ((position, version) in items) {
                if (peer.wants.size >= FastTerrain.WINDOW) break
                if (inRange(peer, position)) peer.wants.putIfAbsent(position, version)
            }
        } catch (_: IllegalArgumentException) {
            /* Reject malformed lengths before scheduling any work. */
        }
    }

    private fun radius(peer: Peer) =
        minOf(peer.radius, LodLimits.radius(plugin.config.getInt("lod.radius-chunks", 1000)))

    private fun inRange(peer: Peer, position: Long): Boolean {
        val player = Bukkit.getPlayer(peer.player) ?: return false
        val r = radius(peer).toLong()
        return kotlin.math.abs(Wire.x(position).toLong() - (player.location.blockX shr 4)) <= r &&
            kotlin.math.abs(Wire.z(position).toLong() - (player.location.blockZ shr 4)) <= r
    }

    private fun config(peer: Peer) {
        val player = Bukkit.getPlayer(peer.player) ?: return
        val allowed = access.valid(peer.lease)
        val frame =
            Wire.Writer()
                .varInt(1)
                .i64(peer.token)
                .bool(allowed)
                .varInt(radius(peer))
                .utf(if (allowed) player.world.key.toString() else "")
                .utf(if (allowed) serverIdentity + ":" + player.world.uid else "")
                .result()
        raw.send(player, FastTerrain.CONFIG, frame)
    }

    private fun main(block: () -> Unit) {
        if (!closed && plugin.isEnabled)
            Bukkit.getScheduler().runTask(plugin, Runnable { if (!closed) block() })
    }

    private fun tick() {
        if (closed) return
        ticks++
        val duty = plugin.backgroundLoad.duty
        var budget = plugin.config.getInt("lod.native-columns-per-tick", 512).coerceIn(16, 2048)
        val ordered =
            peers.values.toList().let {
                if (it.isEmpty()) it
                else it.drop((ticks % it.size).toInt()) + it.take((ticks % it.size).toInt())
            }
        for (peer in ordered) {
            val player = Bukkit.getPlayer(peer.player)
            if (player == null || !player.isOnline) {
                peers.remove(peer.player)
                continue
            }
            val lease = access.issue(peer.player, player.world.uid)
            if (lease != peer.lease) {
                peer.lease = lease
                peer.index = emptyList()
                peer.cursor = 0
                peer.lastScan = 0
                peer.wants.clear()
                config(peer)
            }
            if (duty == 0) {
                peer.index = emptyList()
                peer.cursor = 0
                peer.indexReady = false
                peer.doneSent = false
            }
            if (lease == null) continue
            if (
                duty > 0 &&
                    indexed &&
                    !peer.scanning &&
                    peer.cursor >= peer.index.size &&
                    ticks - peer.lastScan >= 100
            ) {
                peer.scanning = true
                peer.lastScan = ticks
                val x = player.location.blockX shr 4
                val z = player.location.blockZ shr 4
                val radius = radius(peer)
                try {
                    workers.execute {
                        val entries =
                            try {
                                catalog
                                    .nearby(lease.world, x, z, radius)
                                    .onEach {
                                        if (closed || plugin.backgroundLoad.duty == 0)
                                            throw java.util.concurrent.CancellationException(
                                                "server pressure"
                                            )
                                    }
                                    .sortedBy {
                                        maxOf(
                                            kotlin.math.abs(it.first.x.toLong() - x),
                                            kotlin.math.abs(it.first.z.toLong() - z),
                                        )
                                    }
                                    .toList()
                            } catch (_: java.util.concurrent.CancellationException) {
                                null
                            }
                        main {
                            peer.scanning = false
                            if (
                                entries != null &&
                                    plugin.backgroundLoad.duty > 0 &&
                                    peer.lease == lease &&
                                    peers[peer.player] === peer
                            ) {
                                peer.index = entries
                                peer.cursor = 0
                                peer.indexReady = true
                                peer.doneSent = false
                            }
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    peer.scanning = false
                }
            }
            if (peer.indexReady && (!peer.doneSent || peer.cursor < peer.index.size)) {
                val end = minOf(peer.index.size, peer.cursor + 1024)
                val entries =
                    peer.index.subList(peer.cursor, end).filter {
                        catalog[it.first] == it.second &&
                            inRange(peer, Wire.pack(it.first.x, it.first.z))
                    }
                peer.cursor = end
                peer.doneSent = end == peer.index.size
                val frame =
                    Wire.Writer()
                        .varInt(1)
                        .i64(peer.token)
                        .utf(player.world.key.toString())
                        .bool(end == peer.index.size)
                        .varInt(entries.size)
                entries.forEach { (key, version) ->
                    frame.i64(Wire.pack(key.x, key.z)).i64(version)
                }
                access.send(lease) { raw.send(player, FastTerrain.INDEX, frame.result()) }
            }
            if (peer.reading || jobs >= 2 || peer.wants.isEmpty() || budget <= 0) continue
            val count = minOf(512, budget, peer.wants.size)
            val requests = peer.wants.entries.take(count).map { it.key to it.value }
            requests.forEach { peer.wants.remove(it.first) }
            peer.reading = true
            jobs++
            budget -= count
            try {
                workers.execute {
                    val ready = mutableListOf<TerrainBatch.Item>()
                    var bytes = 0
                    var consumed = 0
                    try {
                        for ((position, version) in requests) {
                            if (bytes >= 2 * 1024 * 1024) break
                            consumed++
                            val key =
                                ColumnCache.Key(lease.world, Wire.x(position), Wire.z(position))
                            val current = catalog[key] ?: 0L
                            if (current == 0L) ready += TerrainBatch.Item(position, 2)
                            else if (current == version)
                                ready += TerrainBatch.Item(position, 1, current)
                            else {
                                val body = prepared(key, current)
                                if (body == null) ready += TerrainBatch.Item(position, 2)
                                else {
                                    ready += TerrainBatch.Item(position, 0, current, body)
                                    bytes += body.size
                                }
                            }
                        }
                    } catch (error: Exception) {
                        plugin.logger.warning(
                            "Native terrain read failed: ${error.javaClass.simpleName}"
                        )
                    }
                    main {
                        peer.reading = false
                        jobs--
                        if (
                            peer.lease != lease ||
                                !access.valid(lease) ||
                                peers[peer.player] !== peer
                        )
                            return@main
                        requests.drop(consumed).forEach {
                            peer.wants.putIfAbsent(it.first, it.second)
                        }
                        ready
                            .filter { it.type == 2 && inRange(peer, it.position) }
                            .forEach { regenerate(peer.player, it.position) }
                        val valid = ready.filter {
                            inRange(peer, it.position) &&
                                (it.type == 2 ||
                                    catalog[
                                        ColumnCache.Key(
                                            lease.world,
                                            Wire.x(it.position),
                                            Wire.z(it.position),
                                        )] == it.timestamp)
                        }
                        for (frame in
                            TerrainBatch.encode(
                                player.world.key.toString(),
                                valid,
                                TerrainBatch.MAX_BYTES - 8,
                            )) {
                            access.send(lease) {
                                raw.send(
                                    player,
                                    FastTerrain.DATA,
                                    Wire.Writer().i64(peer.token).raw(frame).result(),
                                )
                            }
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                peer.reading = false
                jobs--
                requests.forEach { peer.wants.putIfAbsent(it.first, it.second) }
            }
        }
        // Precompute in small background jobs before anyone needs the data, without loading worlds.
        if (duty > 0 && indexed && jobs == 0 && ticks % 20 == 0L) {
            val world =
                Bukkit.getWorlds().firstOrNull {
                    access.cacheAllowed(it.uid) && !warming.containsKey(it.uid)
                } ?: return
            val worldId = world.uid
            warming[worldId] = 0
            jobs++
            try {
                workers.execute {
                    try {
                        val slice =
                            plugin.backgroundLoad.reader { closed || !access.cacheAllowed(worldId) }
                        source.forEachCached(worldId, { slice.checkpoint() }) { key, version ->
                            if (catalog[key] == version) prepared(key, version)
                        }
                        main { warming[worldId] = -1 }
                    } catch (_: InterruptedException) {
                        main { warming.remove(worldId) }
                    } catch (error: Exception) {
                        plugin.logger.warning(
                            "Native terrain prewarm failed: ${error.javaClass.simpleName}"
                        )
                        main { warming.remove(worldId) }
                    } finally {
                        main { jobs-- }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                warming.remove(worldId)
                jobs--
            }
        }
    }

    /**
     * The completed catalog tracks atomic writes/invalidations; payloads remain validated on read.
     */
    fun hasCached(key: ColumnCache.Key): Boolean? {
        if (!indexed || plugin.config.getInt("lod.cache-max-age-seconds", -1) >= 0) return null
        return (catalog[key] ?: 0L) > 0
    }

    fun invalidate(key: ColumnCache.Key) {
        catalog[key] = 0
    }

    private fun prepared(key: ColumnCache.Key, version: Long): ByteArray? {
        regions.get(key, version)?.let {
            return it
        }
        if (plugin.cacheQuota.pauseReason() != null) return null
        val entry = source.get(key) ?: return null
        if (source.version(key) != version) return null
        val body = PreparedColumn.prepare(entry.body, opacity::of)
        if (catalog[key] != version) return null
        regions.put(key, version, body)
        return body
    }

    override fun close() {
        closed = true
        plugin.backgroundLoad.stateListeners.remove(cacheMemoryListener)
        source.changed = { _, _ -> }
        peers.clear()
        workers.shutdownNow()
    }
}
