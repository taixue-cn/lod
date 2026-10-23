package cc.taixue.lod.client

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
    private val pending = mutableMapOf<Long, Long>()
    private var bridge: VoxyBridge? = null
    private lateinit var exchange: CacheExchange
    private lateinit var preferences: WorldPreferences
    private var lastPreference: ByteArray? = null
    private var level: ClientLevel? = null
    private var enabled = false
    private var handshook = false
    private var distance = 64
    private var ticks = 0
    private var cursor = 0
    private var errors = 0L

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
        preferences = WorldPreferences()
        exchange = CacheExchange(::send)
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
        logger.info("LodClient initialized; native Voxy/DH behavior remains available")
    }

    private fun reset() {
        if (::exchange.isInitialized) exchange.reset()
        enabled = false
        handshook = false
        lastPreference = null
        level = null
        ticks = 0
        cursor = 0
        timestamps.clear()
        pending.clear()
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
            send(HANDSHAKE, Wire.Writer().varInt(20).varInt(1).result())
            send("taixue:lod_hello", Wire.Writer().varInt(1).result())
            handshook = true
        }
        if (bridge == null || !enabled || !preferences.allows() || ticks % 4 != 0) return
        val playerX = player.chunkPosition().x
        val playerZ = player.chunkPosition().z
        val radius = minOf(distance, preferences.radius())
        val total = (radius * 2 + 1) * (radius * 2 + 1)
        val wants = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        for (checked in 0 until 512) {
            if (wants.size >= 16) break
            if (cursor >= total) cursor = 0
            val offset = spiral(cursor++)
            val x = playerX + offset[0]
            val z = playerZ + offset[1]
            if (abs(offset[0]) > radius || abs(offset[1]) > radius) continue
            // Vanilla owns nearby chunks; this receiver supplements distant terrain only.
            if (
                abs(offset[0]) <= client.options.effectiveRenderDistance &&
                    abs(offset[1]) <= client.options.effectiveRenderDistance
            )
                continue
            val position = Wire.pack(x, z)
            if (now - (pending[position] ?: 0L) < 15000) continue
            val previous = timestamps[position]
            if (previous != null && now - previous < 60000) continue
            exchange.consider(x, z)
            wants += position
            pending[position] = now
        }
        if (wants.isNotEmpty()) {
            val output = Wire.Writer().varInt(wants.size)
            wants.forEach { output.i64(it).i64(timestamps[it] ?: 0L) }
            send(REQUEST, output.result())
        }
        while (timestamps.size > 150000) timestamps.remove(timestamps.keys.first())
        if (pending.size > 20000) pending.entries.removeIf { now - it.value > 15000 }
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
            val reader = Wire.Reader(data)
            if (channel == "lss:session_config") {
                if (reader.varInt() != 20) return
                enabled = reader.bool()
                distance = reader.varInt().coerceIn(8, 128)
                reader.bool()
                if (reader.remaining() > 0) reader.varInt()
                if (reader.remaining() > 0) reader.varInt()
                reader.end()
                if (!enabled) pending.clear()
                return
            }
            val world = level ?: return
            if (!enabled || !preferences.allows() || Minecraft.getInstance().level !== world) return
            when (channel) {
                "lss:voxel_column" -> {
                    val x = reader.i32()
                    val z = reader.i32()
                    val dimension = reader.utf(256)
                    val stamp = reader.i64()
                    reader.u8()
                    val codec = reader.u8()
                    val body = reader.bytes(Wire.MAX_FRAME)
                    reader.end()
                    if (codec != 0 || world.dimension().identifier().toString() != dimension) return
                    val position = Wire.pack(x, z)
                    if (position !in pending) return
                    if (bridge?.ingest(world, x, z, body) == true) {
                        timestamps[position] = stamp
                        pending.remove(position)
                    }
                }
                "lss:batch_response" -> {
                    repeat(reader.count(4096)) {
                        val type = reader.u8()
                        val position = reader.i64()
                        if (type == 1 || type == 2) {
                            pending.remove(position)
                            timestamps[position] = System.currentTimeMillis()
                        }
                    }
                    reader.end()
                }
                "lss:dirty_columns" -> {
                    repeat(reader.count(10000)) {
                        val position = reader.i64()
                        timestamps.remove(position)
                        pending.remove(position)
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

    private fun warn(error: Exception) {
        if (++errors == 1L || errors % 100 == 0L)
            logger.warn("LOD frame could not be ingested ({}): {}", errors, error.toString())
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
