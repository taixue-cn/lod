package cc.taixue.lod.server

import no.jckf.dhsupport.bukkit.BukkitScheduler
import no.jckf.dhsupport.bukkit.commands.LodCommand
import no.jckf.dhsupport.bukkit.handler.ConfigLoader
import no.jckf.dhsupport.bukkit.handler.PlayerHandler
import no.jckf.dhsupport.bukkit.handler.PluginMessageProxy
import no.jckf.dhsupport.bukkit.handler.WorldHandler
import no.jckf.dhsupport.core.DhSupport
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin

/** Paper entry point for the shared Voxy/DH server cache. */
class LodServer : JavaPlugin() {
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

    lateinit var backgroundLoad: BackgroundLoad
        private set

    private var scheduler: BukkitScheduler? = null
    private var configLoader: ConfigLoader? = null
    private var proxy: PluginMessageProxy? = null
    private var worldEdit: WorldEditInvalidation? = null
    private var managementConfiguration: Map<String, Any>? = null

    @Volatile
    var managedWorlds: ManagedWorlds? = null
        private set

    override fun onEnable() {
        check(server.pluginManager.getPlugin("ArchLOD") == null) {
            "Remove the old ArchLOD plugin jar before starting LodServer; data was not moved."
        }
        LegacyDataDirectory.migrate(
            dataFolder.toPath().resolveSibling("ArchLOD"),
            dataFolder.toPath(),
        )
        saveDefaultConfig()
        dhSupport =
            DhSupport(pluginMeta.version, server.minecraftVersion).apply {
                logger = this@LodServer.logger
                dataDirectory = dataFolder.absolutePath
            }
        loadDhsConfig()
        backgroundLoad =
            BackgroundLoad(
                "LOD-load-monitor",
                config.getDouble("prewarm.global.max-heap-percent", 70.0).coerceIn(10.0, 95.0),
            )
        server.scheduler.runTaskTimer(
            this,
            Runnable { backgroundLoad.heartbeat(server.averageTickTime) },
            1,
            1,
        )
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
        reloadManagement()
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
        getCommand("lods")!!.setExecutor(LodCommand(this))
        logger.info(
            "LodServer ready: Voxy LSS v20 + DH v16, world permission enforced, existing terrain only"
        )
    }

    override fun onDisable() {
        if (::backgroundLoad.isInitialized) backgroundLoad.close()
        managedWorlds?.close()
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
        if (::warmService.isInitialized) {
            reloadManagement()
            warmService.reload()
        }
    }

    private fun reloadManagement() {
        val settings =
            config.getConfigurationSection("management")?.getValues(false)?.toMap().orEmpty()
        val enabled = config.getBoolean("management.enabled", false)
        // Tuning budgets must not drop permission leases or restart all world scans.
        if (settings == managementConfiguration && (managedWorlds != null || !enabled)) return
        managementConfiguration = settings
        managedWorlds?.close()
        managedWorlds = null
        if (enabled) {
            try {
                managedWorlds = ManagedWorlds(this)
            } catch (_: IllegalArgumentException) {
                logger.warning("LOD management configuration invalid; world access remains denied")
            }
        }
    }

    fun getWorld(name: String): World? =
        server.worlds.firstOrNull { it.name.replace(' ', '_') == name }
}
