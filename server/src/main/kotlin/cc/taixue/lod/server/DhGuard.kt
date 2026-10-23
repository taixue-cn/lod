package cc.taixue.lod.server

import cc.taixue.lod.core.SessionGate
import java.util.UUID
import no.jckf.dhsupport.core.DhSupport
import no.jckf.dhsupport.core.handler.PluginMessageHandler
import no.jckf.dhsupport.core.message.plugin.PluginMessage
import no.jckf.dhsupport.core.message.plugin.RemotePlayerConfigMessage
import org.bukkit.Bukkit

/** Native DH and Voxy share the same world-access leases. */
object DhGuard {
    private var access: WorldAccess? = null
    private var messages: PluginMessageHandler? = null
    private val lastState = mutableMapOf<UUID, String>()

    @JvmStatic
    fun install(worldAccess: WorldAccess, handler: PluginMessageHandler) {
        access = worldAccess
        messages = handler
    }

    @JvmStatic
    fun issue(player: UUID, world: UUID): SessionGate.Lease? = access?.issue(player, world)

    @JvmStatic fun cacheAllowed(world: UUID): Boolean = access?.cacheAllowed(world) == true

    @JvmStatic
    fun inRange(lease: SessionGate.Lease?, x: Long, z: Long): Boolean =
        access?.inRange(lease, x, z) == true

    @JvmStatic
    fun send(lease: SessionGate.Lease?, message: PluginMessage) {
        val access = access ?: return
        val messages = messages ?: return
        if (lease != null) access.send(lease) { messages.sendPluginMessage(lease.player, message) }
    }

    @JvmStatic
    fun watch(service: DhSupport) {
        val access = access ?: return
        val messages = messages ?: return
        lastState.keys.removeIf { Bukkit.getPlayer(it) == null }
        for (id in service.playerConfigurations.keys.toList()) {
            val player = Bukkit.getPlayer(id) ?: continue
            val world = player.world.uid
            val config = service.getPlayerConfiguration(id, world) ?: continue
            val lease = access.issue(id, world)
            val state = "$world:${lease?.epoch ?: "denied"}"
            if (lastState.put(id, state) == state) continue
            val reply = RemotePlayerConfigMessage().apply { fromConfiguration(config) }
            if (lease == null) {
                reply.setDistantGenerationEnabled(false)
                reply.setRealTimeUpdatesEnabled(false)
                messages.sendPluginMessage(id, reply)
            } else {
                send(lease, reply)
            }
        }
    }

    @JvmStatic
    fun clear() {
        access = null
        messages = null
        lastState.clear()
    }
}
