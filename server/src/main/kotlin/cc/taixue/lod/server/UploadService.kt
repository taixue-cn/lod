package cc.taixue.lod.server

import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.core.ColumnValidation
import cc.taixue.lod.core.SessionGate
import cc.taixue.lod.core.Wire
import cc.taixue.lod.core.WorldPolicy
import dev.vox.lss.common.wire.WireSectionCursor
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** Opt-in, current-world-only exchange. Each upload requires a server request and a live lease. */
class UploadService(
    private val plugin: JavaPlugin,
    private val access: WorldAccess,
    private val raw: RawSender,
    private val terrain: VoxyService,
) : PluginMessageListener {
    private data class Peer(val lease: SessionGate.Lease, val nonce: Long)

    private class Incoming(
        peer: Peer,
        val pos: Long,
        val claimedTime: Long,
        val hash: ByteArray,
        size: Int,
    ) {
        val lease = peer.lease
        val nonce = peer.nonce
        val deadline = System.currentTimeMillis() + 15000
        val body = ByteArray(size)
        var received = 0
    }

    private val random = SecureRandom()
    private val peers = mutableMapOf<UUID, Peer>()
    private val incoming = mutableMapOf<UUID, Incoming>()
    private val rates = mutableMapOf<UUID, LongArray>()
    private val lastOffers = mutableMapOf<UUID, Long>()
    private var accepted = 0L
    private var rejected = 0L

    init {
        val messenger = Bukkit.getMessenger()
        listOf(HELLO, OFFER, PART, PREF).forEach {
            messenger.registerIncomingPluginChannel(plugin, it, this)
        }
        listOf(POLICY, WANT).forEach { messenger.registerOutgoingPluginChannel(plugin, it) }
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                Runnable {
                    incoming.entries.removeIf {
                        System.currentTimeMillis() > it.value.deadline ||
                            !access.valid(it.value.lease)
                    }
                    peers.entries.removeIf { !access.valid(it.value.lease) }
                    lastOffers.keys.removeIf { Bukkit.getPlayer(it) == null }
                    rates.keys.removeIf { Bukkit.getPlayer(it) == null }
                },
                20,
                20,
            )
    }

    private fun trusted(player: Player): Boolean {
        if (!plugin.config.getBoolean("upload.enabled", false)) return false
        if (matches(plugin.config.getStringList("upload.blocked-players"), player)) return false
        return matches(plugin.config.getStringList("upload.allowed-players"), player) ||
            player.hasPermission("archlod.upload")
    }

    private fun matches(names: List<String>, player: Player): Boolean = names.any {
        it == "*" || it.equals(player.name, true) || it.equals(player.uniqueId.toString(), true)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (message.size > 32768) return
        val rate = rates.getOrPut(player.uniqueId) { LongArray(2) }
        val second = System.currentTimeMillis() / 1000
        if (rate[0] != second) {
            rate[0] = second
            rate[1] = 0
        }
        if (++rate[1] > 64) return
        try {
            val reader = Wire.Reader(message)
            if (channel == PREF) {
                val mode = reader.utf(16)
                val entries = List(reader.count(64)) { reader.utf(256) }
                reader.end()
                access.preference(player.uniqueId, WorldPolicy(mode, entries))
                return
            }
            if (channel == HELLO) {
                if (reader.varInt() != 1) return
                reader.end()
                val lease = access.issue(player.uniqueId, player.world.uid) ?: return
                val peer = Peer(lease, random.nextLong())
                peers[player.uniqueId] = peer
                incoming.remove(player.uniqueId)
                val response =
                    Wire.Writer()
                        .varInt(1)
                        .utf("taixue-arch")
                        .utf(lease.world.toString())
                        .utf(player.world.key.toString())
                        .i64(peer.nonce)
                        .bool(trusted(player))
                        .result()
                access.send(lease) { raw.send(player, POLICY, response) }
                return
            }
            val peer = peers[player.uniqueId] ?: return
            if (!access.valid(peer.lease) || !trusted(player)) return
            val nonce = reader.i64()
            val pos = reader.i64()
            if (
                nonce != peer.nonce ||
                    !access.inRange(peer.lease, Wire.x(pos).toLong(), Wire.z(pos).toLong())
            )
                return
            when (channel) {
                OFFER -> {
                    val time = reader.i64()
                    val size =
                        reader.count(
                            plugin.config
                                .getInt("upload.max-column-bytes", 524288)
                                .coerceIn(1024, 524288)
                        )
                    val hash = reader.raw(32)
                    reader.end()
                    val now = System.currentTimeMillis()
                    if (time < 0 || time > now + 60000 || size < 2 || player.uniqueId in incoming)
                        return
                    val cooldown =
                        plugin.config.getInt("upload.cooldown-seconds", 5).coerceIn(1, 60) * 1000L
                    if (now - (lastOffers[player.uniqueId] ?: 0L) < cooldown) return
                    lastOffers[player.uniqueId] = now
                    if (incoming.size >= maxInflight()) return
                    terrain.wantsUpload(
                        ColumnCache.Key(peer.lease.world, Wire.x(pos), Wire.z(pos)),
                        time,
                    ) { wanted ->
                        if (
                            !wanted ||
                                !access.valid(peer.lease) ||
                                !trusted(player) ||
                                peers[player.uniqueId] !== peer ||
                                player.uniqueId in incoming ||
                                incoming.size >= maxInflight()
                        )
                            return@wantsUpload
                        incoming[player.uniqueId] = Incoming(peer, pos, time, hash, size)
                        access.send(peer.lease) {
                            raw.send(player, WANT, Wire.Writer().i64(nonce).i64(pos).result())
                        }
                    }
                }
                PART -> {
                    val upload = incoming[player.uniqueId] ?: return
                    if (upload.pos != pos || upload.nonce != nonce) return
                    val offset = reader.i32()
                    val bytes = reader.bytes(28000)
                    reader.end()
                    if (
                        offset != upload.received ||
                            bytes.isEmpty() ||
                            offset + bytes.size > upload.body.size ||
                            System.currentTimeMillis() > upload.deadline
                    ) {
                        incoming.remove(player.uniqueId)
                        rejected++
                        return
                    }
                    bytes.copyInto(upload.body, offset)
                    upload.received += bytes.size
                    if (upload.received == upload.body.size) {
                        incoming.remove(player.uniqueId)
                        if (
                            !MessageDigest.isEqual(
                                upload.hash,
                                MessageDigest.getInstance("SHA-256").digest(upload.body),
                            )
                        ) {
                            rejected++
                            return
                        }
                        val world = player.world
                        val column =
                            ColumnValidation.validate(
                                upload.body,
                                world.minHeight / 16,
                                world.maxHeight / 16,
                            )
                        validateIdentities(column)
                        terrain.mergeUpload(
                            upload.lease,
                            ColumnCache.Key(upload.lease.world, Wire.x(pos), Wire.z(pos)),
                            column,
                        ) {
                            if (it) accepted++ else rejected++
                        }
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            incoming.remove(player.uniqueId)
            rejected++
        } catch (_: NoSuchAlgorithmException) {
            incoming.remove(player.uniqueId)
            rejected++
        }
    }

    private fun maxInflight() = plugin.config.getInt("upload.max-inflight", 4).coerceIn(1, 16)

    private fun validateIdentities(column: WireSectionCursor.WireColumn) {
        val checkedBlocks = mutableSetOf<Int>()
        val checkedBiomes = mutableSetOf<Int>()
        for (section in column.sections()) {
            for (id in section.blocks().palette()) {
                if (checkedBlocks.add(id)) Bukkit.createBlockData(column.dictionary()[id])
            }
            for (id in section.biomes().palette()) {
                if (!checkedBiomes.add(id)) continue
                val key = NamespacedKey.fromString(column.dictionary()[id])
                require(key != null && Registry.BIOME.get(key) != null) { "unknown biome" }
            }
        }
    }

    fun accepted(): Long = accepted

    fun rejected(): Long = rejected

    fun close() {
        peers.clear()
        incoming.clear()
        lastOffers.clear()
        rates.clear()
    }

    companion object {
        const val HELLO = "taixue:lod_hello"
        const val POLICY = "taixue:lod_policy"
        const val OFFER = "taixue:lod_offer"
        const val WANT = "taixue:lod_want"
        const val PART = "taixue:lod_part"
        const val PREF = "taixue:lod_pref"
    }
}
