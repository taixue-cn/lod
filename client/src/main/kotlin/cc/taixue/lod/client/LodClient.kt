package cc.taixue.lod.client

import cc.taixue.lod.core.FastTerrain
import cc.taixue.lod.core.LodLimits
import cc.taixue.lod.core.RequestWindow
import cc.taixue.lod.core.TerrainBatch
import cc.taixue.lod.core.Wire
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/**
 * Optional LSS v20 receiver; vanilla chunks and Voxy's rendering settings stay under their owners.
 */
class LodClient : ClientModInitializer {
    private val types = mutableMapOf<String, CustomPacketPayload.Type<Frame>>()
    private val timestamps = linkedMapOf<Long, Long>()
    private val requests = RequestWindow()
    private val pending = requests.pending

    private data class Queued(val world: ClientLevel, val item: TerrainBatch.Item)

    private val ingestion = ArrayDeque<Queued>()
    private var ingestionBytes = 0
    private var batchFrames = 0L
    private var maxBatchModels = 0
    private var unchangedColumns = 0L
    private var bridge: VoxyBridge? = null
    private var fast: FastTerrainClient? = null
    private lateinit var exchange: CacheExchange
    private lateinit var preferences: WorldPreferences
    private var lastPreference: ByteArray? = null
    private var level: ClientLevel? = null
    private var enabled = false
    private var handshook = false
    private var distance = LodLimits.DEFAULT_RADIUS_CHUNKS
    private var ticks = 0
    private var cursor = 0
    private var errors = 0L
    private var sessionReceived = false
    private var receivedColumns = 0L
    private var queuedColumns = 0L
    private var ignoredColumns = 0L

    override fun onInitializeClient() {
        val loader = FabricLoader.getInstance()
        if (listOf("lss", "lod_server_support", "voxy_server_side").any(loader::isModLoaded)) {
            logger.info("Public LSS/VSS installed; yielding all terrain channels to it")
            return
        }
        bridge =
            try {
                VoxyBridge()
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: LinkageError) {
                null
            }
        if (bridge == null) logger.info("Voxy unavailable; server terrain bridge remains inactive")
        for (channel in
            listOf(
                FastTerrain.HELLO,
                FastTerrain.REQUEST,
                HANDSHAKE,
                INFO,
                REQUEST,
                "taixue:lod_hello",
                "taixue:lod_offer",
                "taixue:lod_part",
                "taixue:lod_pref",
            )) {
            val type = Frame.type(channel)
            types[channel] = type
            PayloadTypeRegistry.playC2S().register(type, Frame.codec(type, 32768))
        }
        for (channel in
            listOf(
                FastTerrain.CONFIG,
                FastTerrain.INDEX,
                FastTerrain.DATA,
                TerrainBatch.CHANNEL,
                "lss:session_config",
                "lss:voxel_column",
                "lss:batch_response",
                "lss:dirty_columns",
                "taixue:lod_policy",
                "taixue:lod_want",
            )) {
            val type = Frame.type(channel)
            types[channel] = type
            PayloadTypeRegistry.playS2C().register(type, Frame.codec(type, Wire.MAX_FRAME))
            ClientPlayNetworking.registerGlobalReceiver(type) { frame, context ->
                context.client().execute { receive(channel, frame.bytes) }
            }
        }
        bridge?.let {
            try {
                fast = FastTerrainClient(::send, it, loader.gameDir.resolve("lod-native-cache-v1"))
            } catch (error: ReflectiveOperationException) {
                logger.info("Prepared native cache API unavailable: {}", error.toString())
            }
        }
        preferences = WorldPreferences(::status)
        exchange = CacheExchange(::send)
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
        logger.info("LodClient initialized; native Voxy/DH behavior remains available")
    }

    private fun reset() {
        fast?.reset()
        if (::exchange.isInitialized) exchange.reset()
        enabled = false
        sessionReceived = false
        resetCounters()
        handshook = false
        lastPreference = null
        level = null
        ticks = 0
        cursor = 0
        timestamps.clear()
        pending.clear()
        ingestion.clear()
        ingestionBytes = 0
    }

    private fun tick(client: Minecraft) {
        ticks++
        val world = client.level ?: return
        val player = client.player ?: return
        if (ticks % 10 == 0 && ClientPlayNetworking.canSend(types.getValue("taixue:lod_pref"))) {
            val preference = preferences.frame()
            if (!preference.contentEquals(lastPreference)) {
                send("taixue:lod_pref", preference)
                lastPreference = preference
            }
        }
        if (world !== level) {
            exchange.reset()
            level = world
            timestamps.clear()
            pending.clear()
            cursor = 0
            resetCounters()
            ingestion.clear()
            ingestionBytes = 0
            if (handshook) send("taixue:lod_hello", Wire.Writer().varInt(1).result())
        }
        if (
            bridge != null &&
                !handshook &&
                ticks % 20 == 0 &&
                ClientPlayNetworking.canSend(types.getValue(HANDSHAKE))
        ) {
            send(
                INFO,
                Wire.Writer()
                    .varInt(SharedConstants.getCurrentVersion().dataVersion().version())
                    .result(),
            )
            send(HANDSHAKE, Wire.Writer().varInt(20).varInt(1 or TerrainBatch.CAPABILITY).result())
            send("taixue:lod_hello", Wire.Writer().varInt(1).result())
            handshook = true
        }
        if (bridge != null && (!enabled || !preferences.allows()))
            fast?.tick(client, false, preferences.radius())
        if (bridge == null || !enabled || !preferences.allows()) {
            ingestion.clear()
            ingestionBytes = 0
            return
        }
        fast?.tick(client, enabled && preferences.allows(), preferences.radius())
        if (fast?.enabled == true) {
            pending.clear()
            ingestion.clear()
            ingestionBytes = 0
            return
        }
        // Bound main-thread ingestion independently of packet size and keep render frames
        // responsive.
        val until = System.nanoTime() + 4_000_000
        var processed = 0
        while (ingestion.isNotEmpty() && processed < 8 && System.nanoTime() < until) {
            val queued = ingestion.removeFirst()
            ingestionBytes -= queued.item.body.size
            if (queued.world === world && queued.item.position in pending)
                ingest(world, queued.item)
            processed++
        }
        if (ingestionBytes >= 4 * 1024 * 1024) return
        val playerX = player.chunkPosition().x
        val playerZ = player.chunkPosition().z
        val radius = minOf(distance, preferences.radius())
        val total = (radius * 2 + 1) * (radius * 2 + 1)
        val now = System.currentTimeMillis()
        // Retire old-center and newly loaded vanilla chunks before allocating more requests.
        pending.keys.removeIf {
            abs(playerX - Wire.x(it)) > radius ||
                abs(playerZ - Wire.z(it)) > radius ||
                world.chunkSource.hasChunk(Wire.x(it), Wire.z(it))
        }
        val wants = requests.retries(now, 32).toMutableList()
        for (checked in 0 until 512) {
            if (wants.size >= 32 || requests.available <= 0) break
            if (cursor >= total) cursor = 0
            val offset = spiral(cursor++)
            val x = playerX + offset[0]
            val z = playerZ + offset[1]
            if (abs(offset[0]) > radius || abs(offset[1]) > radius) continue
            // The server may send fewer chunks than the configured client view distance.
            // Only actual vanilla chunks are excluded, otherwise that gap never gets LOD requests.
            if (world.chunkSource.hasChunk(x, z)) continue
            val position = Wire.pack(x, z)
            if (position in pending) continue
            val previous = timestamps[position]
            if (previous != null && now - previous < 60000) continue
            if (!requests.claim(position, now)) continue
            exchange.consider(x, z)
            wants += position
        }
        if (wants.isNotEmpty()) {
            val output = Wire.Writer().varInt(wants.size)
            wants.forEach { output.i64(it).i64(timestamps[it] ?: 0L) }
            send(REQUEST, output.result())
        }
        while (timestamps.size > 150000) timestamps.remove(timestamps.keys.first())
    }

    private fun receive(channel: String, data: ByteArray) {
        try {
            if (channel == "taixue:lod_policy") {
                exchange.policy(data)
                return
            }
            if (channel == "taixue:lod_want") {
                exchange.want(data)
                return
            }
            if (channel in listOf(FastTerrain.CONFIG, FastTerrain.INDEX, FastTerrain.DATA)) {
                fast?.receive(channel, data, Minecraft.getInstance())
                return
            }
            val reader = Wire.Reader(data)
            if (channel == "lss:session_config") {
                if (reader.varInt() != 20) return
                enabled = reader.bool()
                distance = LodLimits.radius(reader.varInt())
                reader.bool()
                if (reader.remaining() > 0) reader.varInt()
                if (reader.remaining() > 0) reader.varInt()
                reader.end()
                sessionReceived = true
                logger.info("LOD session config received: enabled={}, radius={}", enabled, distance)
                if (!enabled) pending.clear()
                return
            }
            val world = level ?: return
            if (!enabled || !preferences.allows() || Minecraft.getInstance().level !== world) return
            if (
                fast?.enabled == true &&
                    channel in
                        listOf(TerrainBatch.CHANNEL, "lss:voxel_column", "lss:batch_response")
            )
                return
            when (channel) {
                TerrainBatch.CHANNEL -> {
                    val frame = TerrainBatch.decode(data)
                    if (frame.world != world.dimension().identifier().toString()) return
                    batchFrames++
                    maxBatchModels = maxOf(maxBatchModels, frame.items.count { it.type == 0 })
                    for (item in frame.items) {
                        if (item.position !in pending) continue
                        if (item.type == 0) enqueue(world, item)
                        else {
                            if (item.type == 1) unchangedColumns++
                            pending.remove(item.position)
                            timestamps[item.position] = System.currentTimeMillis()
                        }
                    }
                }
                "lss:voxel_column" -> {
                    val x = reader.i32()
                    val z = reader.i32()
                    val dimension = reader.utf(256)
                    val stamp = reader.i64()
                    reader.u8()
                    val codec = reader.u8()
                    val body = reader.bytes(Wire.MAX_FRAME)
                    reader.end()
                    if (codec != 0 || world.dimension().identifier().toString() != dimension) {
                        ignoredColumns++
                        return
                    }
                    enqueue(world, TerrainBatch.Item(Wire.pack(x, z), 0, stamp, body))
                }
                "lss:batch_response" -> {
                    repeat(reader.count(4096)) {
                        val type = reader.u8()
                        val position = reader.i64()
                        if (type == 1 || type == 2) {
                            if (type == 1) unchangedColumns++
                            pending.remove(position)
                            timestamps[position] = System.currentTimeMillis()
                        }
                    }
                    reader.end()
                }
                "lss:dirty_columns" -> {
                    repeat(reader.count(10000)) {
                        val position = reader.i64()
                        fast?.dirty(position)
                        timestamps.remove(position)
                        pending.remove(position)
                        ingestion.removeAll {
                            if (it.item.position == position) {
                                ingestionBytes -= it.item.body.size
                                true
                            } else false
                        }
                    }
                    reader.end()
                }
            }
        } catch (error: ReflectiveOperationException) {
            warn(error)
        } catch (error: RuntimeException) {
            warn(error)
        }
    }

    /**
     * Client-thread counters describe receipt and enqueueing, not Voxy's asynchronous render
     * completion.
     */
    private fun status(): List<String> =
        listOf(
                fast?.status() ?: "原生远景: 当前 Voxy 不支持，使用兼容模式",
                "LOD 世界: ${level?.dimension()?.identifier() ?: "未进入世界"}",
                "Voxy 接口: ${bridge != null}；握手已发送: $handshook；服务器已应答: $sessionReceived",
                "服务器允许下载: $enabled；个人世界设置允许: ${preferences.allows()}",
                "本世界收到模型: $receivedColumns；已提交 Voxy 队列: $queuedColumns；忽略: $ignoredColumns；错误: $errors",
                "批量响应: $batchFrames；缓存有效确认: $unchangedColumns；待处理模型: ${ingestion.size}",
                "等待响应: ${pending.size}；下载半径: ${minOf(distance, preferences.radius())} 区块",
            )
            .also { lines -> lines.forEach { logger.info(it) } }

    private fun resetCounters() {
        batchFrames = 0
        maxBatchModels = 0
        unchangedColumns = 0
        receivedColumns = 0
        queuedColumns = 0
        ignoredColumns = 0
        errors = 0
    }

    private fun warn(error: Exception) {
        if (++errors == 1L || errors % 100 == 0L)
            logger.warn("LOD frame could not be ingested ({}): {}", errors, error.toString())
    }

    private fun enqueue(world: ClientLevel, item: TerrainBatch.Item) {
        receivedColumns++
        if (item.position !in pending || ingestionBytes + item.body.size > 8 * 1024 * 1024) {
            ignoredColumns++
            return
        }
        ingestion.addLast(Queued(world, item))
        ingestionBytes += item.body.size
    }

    private fun ingest(world: ClientLevel, item: TerrainBatch.Item) {
        try {
            if (
                bridge?.ingest(world, Wire.x(item.position), Wire.z(item.position), item.body) ==
                    true
            ) {
                queuedColumns++
                timestamps[item.position] = item.timestamp
                pending.remove(item.position)
            } else ignoredColumns++
        } catch (error: Exception) {
            warn(error)
        }
    }

    private fun send(channel: String, bytes: ByteArray) {
        if (channel in listOf("taixue:lod_offer", "taixue:lod_part") && !preferences.allows())
            return
        val type = types.getValue(channel)
        if (ClientPlayNetworking.canSend(type)) ClientPlayNetworking.send(Frame(type, bytes))
    }

    private companion object {
        const val HANDSHAKE = "lss:handshake_c2s"
        const val INFO = "lss:client_info"
        const val REQUEST = "lss:batch_chunk_req"
        val logger = LoggerFactory.getLogger("TaixueLOD")

        fun spiral(index: Int): IntArray {
            if (index == 0) return intArrayOf(0, 0)
            val ring = ceil((sqrt(index + 1.0) - 1) / 2).toInt()
            val side = 2 * ring
            var offset = index - (2 * ring - 1) * (2 * ring - 1)
            if (offset < side) return intArrayOf(ring, -ring + 1 + offset)
            offset -= side
            if (offset < side) return intArrayOf(ring - 1 - offset, ring)
            offset -= side
            if (offset < side) return intArrayOf(-ring, ring - 1 - offset)
            offset -= side
            return intArrayOf(-ring + 1 + offset, -ring)
        }
    }
}
