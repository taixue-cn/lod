package cc.taixue.lod.client

import cc.taixue.lod.core.Wire
import com.google.gson.JsonParser
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/** Uploads require both an explicit server policy and a locally trusted server address. */
internal class CacheExchange(private val send: (String, ByteArray) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TaixueLOD-cache-offers").apply { isDaemon = true }
    }
    private val trustedAddresses = mutableSetOf<String>()
    private var permitted = false
    private var working = false
    private var nonce = 0L
    private var offeredPos = 0L
    private var lastOffer = 0L
    private var offered: ByteArray? = null
    private var epoch = 0

    init {
        val config = FabricLoader.getInstance().configDir.resolve("taixue-lod-client.json")
        try {
            if (!Files.exists(config))
                Files.writeString(
                    config,
                    """{"shareLocalCache":true,"trustedServers":["mc.taixue.cc","minecraft.taixue.cc","*.taixue.cc"]}""" +
                        "\n",
                )
            val json = JsonParser.parseString(Files.readString(config)).asJsonObject
            if (json.has("shareLocalCache") && json.get("shareLocalCache").asBoolean) {
                json.getAsJsonArray("trustedServers").forEach {
                    trustedAddresses += it.asString.lowercase(Locale.ROOT)
                }
            }
        } catch (_: Exception) {
            trustedAddresses.clear()
        }
    }

    fun reset() {
        epoch++
        permitted = false
        offered = null
        working = false
        lastOffer = 0
    }

    fun policy(frame: ByteArray) {
        reset()
        val reader = Wire.Reader(frame)
        if (reader.varInt() != 1) return
        val serverId = reader.utf(128)
        reader.utf(64)
        val dimension = reader.utf(256)
        nonce = reader.i64()
        val uploads = reader.bool()
        reader.end()
        val client = Minecraft.getInstance()
        val info = client.currentServer ?: return
        val level = client.level ?: return
        val host = info.ip.lowercase(Locale.ROOT).replace(Regex(":\\d+$"), "")
        val trusted =
            host in trustedAddresses ||
                trustedAddresses
                    .filter { it.startsWith("*.") }
                    .any { host.endsWith(it.substring(1)) }
        permitted =
            uploads &&
                trusted &&
                serverId == "taixue-arch" &&
                level.dimension().identifier().toString() == dimension
    }

    fun consider(x: Int, z: Int) {
        if (!permitted || working || System.currentTimeMillis() - lastOffer < 5000) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val generation = epoch
        working = true
        lastOffer = System.currentTimeMillis()
        val pos = Wire.pack(x, z)
        worker.execute {
            val bytes =
                try {
                    VoxyCacheReader.read(level, x, z)
                } catch (_: ReflectiveOperationException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            client.execute {
                if (generation != epoch) return@execute
                working = false
                if (bytes == null || bytes.size > 524288 || client.level !== level) return@execute
                offered = bytes
                offeredPos = pos
                // Voxy has no reliable per-section wall-clock time; zero explicitly means unknown.
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                send(
                    "taixue:lod_offer",
                    Wire.Writer()
                        .i64(nonce)
                        .i64(pos)
                        .i64(0)
                        .varInt(bytes.size)
                        .raw(digest)
                        .result(),
                )
            }
        }
    }

    fun want(frame: ByteArray) {
        val reader = Wire.Reader(frame)
        val requestedNonce = reader.i64()
        val pos = reader.i64()
        reader.end()
        val bytes = offered ?: return
        if (
            !permitted ||
                requestedNonce != nonce ||
                pos != offeredPos ||
                System.currentTimeMillis() - lastOffer > 15000
        )
            return
        for (offset in bytes.indices step 28000) {
            val part = bytes.copyOfRange(offset, minOf(bytes.size, offset + 28000))
            send(
                "taixue:lod_part",
                Wire.Writer().i64(nonce).i64(pos).i32(offset).bytes(part).result(),
            )
        }
        offered = null
    }
}
