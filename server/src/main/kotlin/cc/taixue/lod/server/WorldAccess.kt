package cc.taixue.lod.server

import cc.taixue.lod.core.SessionGate
import cc.taixue.lod.core.WorldPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent

/** Main-thread-owned authorization; unresolved world permissions always deny. */
class WorldAccess(private val plugin: LodServer) : Listener {
    private val gate = SessionGate()
    @Volatile private lateinit var policy: WorldPolicy
    @Volatile private var managed = false
    private val personal = mutableMapOf<UUID, WorldPolicy>()
    private val knownWorlds = ConcurrentHashMap<UUID, Array<String>>()

    init {
        refreshPolicy()
        Bukkit.getWorlds().forEach(::know)
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable {
                plugin.server.onlinePlayers.forEach(::observe)
            },
            1,
            1,
        )
    }

    fun preference(player: UUID, preference: WorldPolicy) {
        personal[player] = preference
        retire(player)
    }

    fun refreshPolicy() {
        managed = plugin.config.getBoolean("management.enabled", false)
        policy =
            WorldPolicy(
                plugin.config.getString("lod.worlds.mode", "blacklist")!!,
                plugin.config.getStringList("lod.worlds.entries"),
            )
    }

    fun cacheAllowed(world: UUID): Boolean {
        if (!knownWorlds.containsKey(world)) return false
        if (managed) {
            return plugin.managedWorlds?.allows(world) == true
        }
        return knownWorlds[world]?.let { policy.allows(*it) } ?: false
    }

    private fun know(world: World) {
        knownWorlds[world.uid] = arrayOf(world.name, world.key.toString(), world.uid.toString())
    }

    @EventHandler fun loadWorld(event: WorldLoadEvent) = know(event.world)

    @EventHandler
    fun unloadWorld(event: WorldUnloadEvent) {
        knownWorlds.remove(event.world.uid)
    }

    private fun allowed(player: Player): Boolean =
        try {
            val permission =
                plugin.config
                    .getString("lod.access-permission", "multiverse.access.%s")!!
                    .format(player.world.name)
            val identifiers = knownWorlds[player.world.uid]
            cacheAllowed(player.world.uid) &&
                identifiers != null &&
                (personal[player.uniqueId]?.allows(*identifiers) != false) &&
                plugin.isEnabled &&
                player.isOnline &&
                player.hasPermission("archlod.use") &&
                player.hasPermission(permission)
        } catch (_: RuntimeException) {
            false
        }

    private fun observe(player: Player): SessionGate.Lease? =
        gate.observe(player.uniqueId, player.world.uid, allowed(player))

    fun issue(player: UUID, world: UUID): SessionGate.Lease? {
        requireMain()
        val online = Bukkit.getPlayer(player) ?: return null
        if (online.world.uid != world) return null
        return observe(online)
    }

    fun valid(lease: SessionGate.Lease?): Boolean {
        requireMain()
        if (lease == null) return false
        val player = Bukkit.getPlayer(lease.player)
        if (player == null || !player.isOnline) {
            gate.remove(lease.player)
            return false
        }
        observe(player)
        return gate.valid(lease)
    }

    fun inRange(lease: SessionGate.Lease?, chunkX: Long, chunkZ: Long): Boolean {
        if (!valid(lease) || abs(chunkX) > 1_875_000 || abs(chunkZ) > 1_875_000) return false
        val player = Bukkit.getPlayer(lease!!.player) ?: return false
        val radius = plugin.config.getInt("lod.radius-chunks", 128).coerceIn(8, 512)
        val x = (player.location.blockX shr 4).toLong()
        val z = (player.location.blockZ shr 4).toLong()
        return abs(x - chunkX) <= radius && abs(z - chunkZ) <= radius
    }

    /** Recheck the lease on the main thread immediately before sending any cached bytes. */
    fun send(lease: SessionGate.Lease?, operation: Runnable) {
        if (!plugin.isEnabled) return
        val guarded = Runnable { if (valid(lease)) operation.run() }
        if (Bukkit.isPrimaryThread()) guarded.run()
        else Bukkit.getScheduler().runTask(plugin, guarded)
    }

    fun retire(player: UUID) = gate.remove(player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun quit(event: PlayerQuitEvent) {
        retire(event.player.uniqueId)
        personal.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun changed(event: PlayerChangedWorldEvent) = retire(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun join(event: PlayerJoinEvent) {
        retire(event.player.uniqueId)
        personal.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun respawn(event: PlayerRespawnEvent) = retire(event.player.uniqueId)

    fun close() {
        personal.clear()
        gate.clear()
        HandlerList.unregisterAll(this)
    }

    private fun requireMain() {
        check(Bukkit.isPrimaryThread()) { "LOD authorization off main thread" }
    }
}
