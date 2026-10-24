package cc.taixue.lod.client

import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.core.FastTerrain
import cc.taixue.lod.core.LodLimits
import cc.taixue.lod.core.NativeRegionStore
import cc.taixue.lod.core.TerrainBatch
import cc.taixue.lod.core.Wire
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.slf4j.LoggerFactory

/** Region discovery, persistent local native cache and bounded off-thread Voxy imports. */
internal class FastTerrainClient(
    private val send: (String, ByteArray) -> Unit,
    bridge: VoxyBridge,
    cacheRoot: Path,
) {
    private data class Want(val version: Long, var requested: Long = 0)

    private val native = NativeVoxy(bridge)
    private val cache = NativeRegionStore(cacheRoot)
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(64),
            { task -> Thread(task, "LOD-native-import").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val buffered = AtomicInteger()
    private val generations = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private var session: NativeVoxy.Session? = null
    private var level: ClientLevel? = null
    private var token = 0L
    private var radius = 0
    @Volatile private var limit = 0
    @Volatile private var centerX = 0
    @Volatile private var centerZ = 0
    private var lastHello = 0L
    private var cacheWorld = UUID(0, 0)
    private var active = false
    private val discovered = linkedMapOf<Long, Long>()
    private val completed = mutableMapOf<Long, Long>()
    private val pending = linkedMapOf<Long, Want>()
    private val network = linkedMapOf<Long, Long>()
    private var localJobs = 0
    private var received = 0L
    private var imported = 0L
    private var local = 0L
    private var unchanged = 0L
    private var errors = 0L
    private var bytes = 0L
    private var indexDone = false
    private var started = 0L
    private var expectedWorld = ""
    val enabled: Boolean
        get() = active

    fun reset() {
        active = false
        token++
        session?.let { old ->
            old.active.set(false)
            worker.execute { old.release() }
        }
        session = null
        level = null
        radius = 0
        lastHello = 0
        discovered.clear()
        completed.clear()
        pending.clear()
        network.clear()
        localJobs = 0
        received = 0
        imported = 0
        local = 0
        unchanged = 0
        errors = 0
        bytes = 0
        indexDone = false
    }

    fun tick(client: Minecraft, allowed: Boolean, requestedRadius: Int) {
        val world = client.level ?: return
        val player = client.player ?: return
        centerX = player.chunkPosition().x
        centerZ = player.chunkPosition().z
        if (level !== world || radius != requestedRadius) {
            reset()
            level = world
            radius = requestedRadius
            limit = requestedRadius
            expectedWorld = world.dimension().identifier().toString()
            cacheWorld =
                UUID.nameUUIDFromBytes(
                    ((client.currentServer?.ip ?: "local") + "\u0000" + expectedWorld).toByteArray()
                )
        }
        if (!allowed) {
            if (active) reset()
            return
        }
        val now = System.currentTimeMillis()
        if (!active && now - lastHello >= 1000) {
            lastHello = now
            send(FastTerrain.HELLO, Wire.Writer().varInt(1).i64(token).varInt(radius).result())
        }
        if (!active) return
        discovered.keys.removeIf { !inRange(it) }
        pending.keys.removeIf { !inRange(it) }
        network.keys.removeIf { !inRange(it) }
        val currentSession = session ?: return
        val epoch = token
        // Stale network work is retried without releasing its bounded window slot.
        pending
            .filterValues { it.requested > 0 && now - it.requested > 30000 }
            .forEach { (position, want) ->
                want.requested = 0
                network[position] = 0
            }
        if (
            localJobs < 2 &&
                buffered.get() < 16 * 1024 * 1024 &&
                pending.size < FastTerrain.WINDOW &&
                discovered.isNotEmpty()
        ) {
            val batch =
                discovered.entries.take(minOf(128, FastTerrain.WINDOW - pending.size)).map {
                    it.key to it.value
                }
            batch.forEach { (position, version) ->
                discovered.remove(position)
                pending[position] = Want(version)
            }
            localJobs++
            val id = cacheWorld
            val revisions = batch.associate { it.first to (generations[it.first] ?: 0L) }
            worker.execute {
                val downloaded = mutableListOf<Pair<Long, Long>>()
                val missing = mutableListOf<Long>()
                for ((position, version) in batch) {
                    if (!currentSession.active.get()) break
                    try {
                        val body =
                            cache.get(
                                ColumnCache.Key(id, Wire.x(position), Wire.z(position)),
                                version,
                            )
                        if (
                            body != null &&
                                currentSession.import(Wire.x(position), Wire.z(position), body) {
                                    (generations[position] ?: 0L) == revisions[position] &&
                                        inRange(position)
                                }
                        )
                            downloaded += position to version
                        else missing += position
                    } catch (_: Exception) {
                        missing += position
                    }
                }
                client.execute {
                    if (epoch != token) return@execute
                    localJobs--
                    for ((position, version) in
                        downloaded.filter {
                            (generations[it.first] ?: 0L) == revisions[it.first]
                        }) {
                        local++
                        imported++
                        completed[position] = version
                        pending.remove(position)
                    }
                    missing.forEach { if (it in pending) network[it] = 0 }
                }
            }
        }
        if (buffered.get() >= 16 * 1024 * 1024 || worker.queue.size >= 32) return
        repeat(4) {
            val batch = network.entries.take(FastTerrain.REQUEST_BATCH).map { it.key to it.value }
            if (batch.isEmpty()) return
            val frame = Wire.Writer().varInt(1).i64(token).varInt(batch.size)
            for ((position, version) in batch) {
                frame.i64(position).i64(version)
                network.remove(position)
                pending[position]?.requested = now
            }
            send(FastTerrain.REQUEST, frame.result())
        }
    }

    fun receive(channel: String, data: ByteArray, client: Minecraft) {
        val reader = Wire.Reader(data)
        if (channel == FastTerrain.CONFIG) {
            require(reader.varInt() == 1)
            if (reader.i64() != token) return
            val allowed = reader.bool()
            val negotiatedRadius = reader.varInt()
            val world = reader.utf(256)
            val namespace = reader.utf(256)
            reader.end()
            if (!allowed || world != expectedWorld) {
                if (active) reset()
                return
            }
            if (!active) {
                cacheWorld =
                    UUID.nameUUIDFromBytes(
                        ((client.currentServer?.ip ?: "local") + "\u0000" + namespace).toByteArray()
                    )
                session = native.session(level ?: return) ?: return
                started = System.currentTimeMillis()
                active = true
            }
            require(negotiatedRadius in LodLimits.MIN_RADIUS_CHUNKS..radius)
            limit = negotiatedRadius
            return
        }
        if (!active) return
        if (channel == FastTerrain.INDEX) {
            require(reader.varInt() == 1)
            if (reader.i64() != token || reader.utf(256) != expectedWorld) return
            val done = reader.bool()
            val entries = mutableListOf<Pair<Long, Long>>()
            repeat(reader.count(1024)) { entries += reader.i64() to reader.i64() }
            reader.end()
            val player = client.player ?: return
            val x = player.chunkPosition().x
            val z = player.chunkPosition().z
            for ((position, version) in entries) {
                if (!inRange(position)) continue
                if (level?.chunkSource?.hasChunk(Wire.x(position), Wire.z(position)) == true) {
                    completed[position] = version
                    continue
                }
                if (
                    completed[position] != version &&
                        position !in pending &&
                        discovered.size < 4_004_001
                )
                    discovered[position] = version
            }
            indexDone = done
        } else if (channel == FastTerrain.DATA) {
            if (reader.i64() != token) return
            val frame = TerrainBatch.decode(reader.raw(reader.remaining()))
            if (frame.world != expectedWorld) return
            val currentSession = session ?: return
            val items = frame.items.filter { it.position in pending && inRange(it.position) }
            val size = items.sumOf { it.body.size }
            if (buffered.get() + size > 32 * 1024 * 1024) return
            for (item in items.filter { it.type != 0 }) {
                if (item.type == 1) unchanged++
                completed[item.position] = pending[item.position]!!.version
                pending.remove(item.position)
            }
            val models = items.filter { it.type == 0 }
            if (models.isEmpty()) return
            received += models.size
            bytes += size
            buffered.addAndGet(size)
            val epoch = token
            val id = cacheWorld
            val revisions = models.associate { it.position to (generations[it.position] ?: 0L) }
            try {
                worker.execute {
                    val importedModels = mutableListOf<TerrainBatch.Item>()
                    var failed = 0
                    try {
                        for (item in models) {
                            if (!currentSession.active.get()) break
                            try {
                                if (
                                    currentSession.import(
                                        Wire.x(item.position),
                                        Wire.z(item.position),
                                        item.body,
                                    ) {
                                        (generations[item.position] ?: 0L) ==
                                            revisions[item.position] && inRange(item.position)
                                    }
                                ) {
                                    cache.put(
                                        ColumnCache.Key(
                                            id,
                                            Wire.x(item.position),
                                            Wire.z(item.position),
                                        ),
                                        item.timestamp,
                                        item.body,
                                    )
                                    importedModels += item
                                }
                            } catch (error: Exception) {
                                failed++
                                logger.warn("Native column import failed: {}", error.toString())
                            }
                        }
                    } finally {
                        buffered.addAndGet(-size)
                    }
                    client.execute {
                        if (epoch != token) return@execute
                        errors += failed
                        for (item in
                            importedModels.filter {
                                (generations[it.position] ?: 0L) == revisions[it.position]
                            }) {
                            imported++
                            completed[item.position] = item.timestamp
                            pending.remove(item.position)
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                buffered.addAndGet(-size)
            }
        }
    }

    private fun inRange(position: Long) =
        kotlin.math.abs(Wire.x(position).toLong() - centerX) <= limit &&
            kotlin.math.abs(Wire.z(position).toLong() - centerZ) <= limit

    fun dirty(position: Long) {
        generations.merge(position, 1L, Long::plus)
        completed.remove(position)
        discovered[position] = 0
        pending.remove(position)
        network.remove(position)
    }

    fun status(): String =
        "原生远景: ${if (active) "同步中" else "未启用"}；索引: ${if (indexDone) "已收到" else "接收中"}；待同步: ${discovered.size}；进行中: ${pending.size}；下载: $received；本地复用: $local；已导入: $imported；错误: $errors；传输: ${bytes / 1024} KiB；耗时: ${if (active) (System.currentTimeMillis() - started) / 1000 else 0}s"

    private companion object {
        val logger = LoggerFactory.getLogger(FastTerrainClient::class.java)
    }
}
