package no.jckf.dhsupport.bukkit

import cc.taixue.lod.server.CacheManager
import cc.taixue.lod.server.DhGuard
import cc.taixue.lod.server.VoxyService
import cc.taixue.lod.server.WarmService
import cc.taixue.lod.server.WorldAccess
import cc.taixue.lod.server.WorldEditInvalidation
import no.jckf.dhsupport.bukkit.commands.LodCommand
import no.jckf.dhsupport.bukkit.handler.ConfigLoader
import no.jckf.dhsupport.bukkit.handler.PlayerHandler
import no.jckf.dhsupport.bukkit.handler.PluginMessageProxy
import no.jckf.dhsupport.bukkit.handler.WorldHandler
import no.jckf.dhsupport.core.DhSupport
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin

/**
 * LOD integration of upstream DH Support. Its package name remains stable for the upstream
 * adapters.
 */
class DhSupportBukkitPlugin : JavaPlugin() {
    lateinit var dhSupport: DhSupport
        private set

    lateinit var worldAccess: WorldAccess
        private set

    lateinit var voxyService: VoxyService
        private set

    lateinit var cacheManager: CacheManager
        private set

    lateinit var warmService: WarmService
        private set

    private var scheduler: BukkitScheduler? = null
    private var configLoader: ConfigLoader? = null
    private var proxy: PluginMessageProxy? = null
    private var worldEdit: WorldEditInvalidation? = null

    override fun onEnable() {
        saveDefaultConfig()
        dhSupport =
            DhSupport(pluginMeta.version, server.minecraftVersion).apply {
                logger = this@DhSupportBukkitPlugin.logger
                dataDirectory = dataFolder.absolutePath
            }
        loadDhsConfig()
        worldAccess = WorldAccess(this)
        DhGuard.install(worldAccess, dhSupport.pluginMessageHandler)
        scheduler = BukkitScheduler(this).also { dhSupport.scheduler = it }
        dhSupport.onEnable()
        server.pluginManager.registerEvents(WorldHandler(this), this)
        server.pluginManager.registerEvents(PlayerHandler(this), this)
        proxy = PluginMessageProxy(this).also { it.onEnable() }
        voxyService = VoxyService(this, worldAccess).also { it.start() }
        cacheManager = CacheManager(this)
        warmService = WarmService(this)
        if (
            server.pluginManager.getPlugin("WorldEdit") != null ||
                server.pluginManager.getPlugin("FastAsyncWorldEdit") != null
        ) {
            try {
                worldEdit = WorldEditInvalidation(this, voxyService, dhSupport)
            } catch (error: LinkageError) {
                logger.warning("WorldEdit cache invalidation unavailable: $error")
            } catch (error: RuntimeException) {
                logger.warning("WorldEdit cache invalidation unavailable: $error")
            }
        }
        scheduler!!.runTimer({ DhGuard.watch(dhSupport) }, 1, 5)
        scheduler!!.runTimer(
            {
                dhSupport.generationTracker.prune()
                dhSupport.updateTouchedLods()
            },
            20,
            maxOf(20, config.getInt("lod_refresh_interval", 15) * 20L),
        )
        getCommand("lod")!!.setExecutor(LodCommand(this))
        logger.info(
            "ARCH LOD ready: Voxy LSS v20 + DH v16, world permission enforced, existing terrain only"
        )
    }

    override fun onDisable() {
        if (::warmService.isInitialized) warmService.close()
        if (::cacheManager.isInitialized) cacheManager.close()
        worldEdit?.close()
        if (::voxyService.isInitialized) voxyService.close()
        proxy?.onDisable()
        if (::worldAccess.isInitialized) worldAccess.close()
        DhGuard.clear()
        scheduler?.cancelTasks()
        if (::dhSupport.isInitialized) dhSupport.onDisable()
    }

    fun loadDhsConfig() {
        configLoader?.onDisable()
        configLoader = ConfigLoader(this).also { it.onEnable() }
        dhSupport.config.set("generate_new_chunks", false)
        dhSupport.config.set("check_for_updates", false)
        if (::worldAccess.isInitialized) worldAccess.refreshPolicy()
        if (::cacheManager.isInitialized) cacheManager.reload()
        if (::warmService.isInitialized) warmService.reload()
    }

    fun getWorld(name: String): World? =
        server.worlds.firstOrNull { it.name.replace(' ', '_') == name }
}
