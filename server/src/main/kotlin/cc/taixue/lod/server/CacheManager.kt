package cc.taixue.lod.server

import cc.taixue.lod.core.CacheRetention
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

/** One retention policy spans compressed Voxy files and DH model payloads. */
class CacheManager(private val plugin: LodServer) : AutoCloseable, Listener {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LOD-cache-retention").apply { isDaemon = true }
    }
    private val worlds = Properties()
    @Volatile private var rules = emptyMap<UUID, CacheRetention.Rule>()
    @Volatile private var global = 0L
    @Volatile private var running = false
    @Volatile private var status = "not scanned"
    private var current: CompletableFuture<String>? = null
    private var lastRun = 0L

    init {
        try {
            Files.newInputStream(index()).use(worlds::load)
        } catch (_: IOException) {
            // The index is optional on first startup; loaded worlds repopulate it below.
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getWorlds().forEach(::know)
        reload()
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                Runnable {
                    if (
                        System.currentTimeMillis() - lastRun >=
                            maxOf(1, plugin.config.getInt("cache.maintenance-seconds", 30)) * 1000L
                    )
                        trim()
                },
                20,
                20,
            )
    }

    private fun index() = plugin.dataFolder.toPath().resolve("cached-worlds.properties")

    private fun know(world: World) {
        worlds.setProperty(world.uid.toString(), world.name)
        try {
            Files.newOutputStream(index()).use {
                worlds.store(it, "Cached world UUIDs; retained when a world unloads")
            }
        } catch (error: IOException) {
            plugin.logger.warning(error.toString())
        }
    }

    @EventHandler
    fun load(event: WorldLoadEvent) {
        know(event.world)
        reload()
    }

    fun reload() {
        global =
            bytes(
                plugin.config.getLong(
                    "cache.global.max-mib",
                    plugin.config.getLong("lod.cache-mib", 4096),
                )
            )
        val snapshot = mutableMapOf<UUID, CacheRetention.Rule>()
        plugin.config.getConfigurationSection("cache.by_world")?.getValues(false)?.forEach {
            (name, value) ->
            if (value is ConfigurationSection) {
                for (id in worlds.stringPropertyNames()) {
                    if (name == id || name == worlds.getProperty(id)) {
                        snapshot[UUID.fromString(id)] =
                            CacheRetention.Rule(
                                if (value.contains("max-mib")) bytes(value.getLong("max-mib"))
                                else null,
                                value.getInt("priority", 0),
                            )
                    }
                }
            }
        }
        rules = snapshot.toMap()
        lastRun = 0
    }

    @Synchronized
    fun trim(): CompletableFuture<String> {
        if (running) return checkNotNull(current)
        running = true
        lastRun = System.currentTimeMillis()
        val result = CompletableFuture<String>()
        current = result
        val limit = global
        val policy = rules
        worker.execute {
            try {
                val entries =
                    plugin.voxyService.cache().entries() +
                        plugin.dhSupport.lodRepository.cacheEntriesAsync().join()
                val victims = CacheRetention.victims(entries, limit, policy)
                victims
                    .filter { it.id.startsWith("voxy:") }
                    .forEach { plugin.voxyService.cache().evict(it) }
                plugin.dhSupport.lodRepository.evictAsync(victims).join()
                val total = entries.sumOf { it.bytes } - victims.sumOf { it.bytes }
                status =
                    "cache bytes=$total, evicted=${victims.size}, limit=$limit" +
                        if (limit >= 0 && total > limit) " (protected worlds exceed global limit)"
                        else ""
                result.complete(status)
            } catch (error: Exception) {
                status = "cache maintenance failed: $error"
                plugin.logger.warning(status)
                result.completeExceptionally(error)
            } finally {
                running = false
            }
        }
        return result
    }

    fun command(sender: CommandSender, args: Array<String>): Boolean {
        when (args.firstOrNull() ?: "status") {
            "status" -> sender.sendMessage(status)
            "trim" ->
                trim().whenComplete { value, error ->
                    Bukkit.getScheduler()
                        .runTask(
                            plugin,
                            Runnable { sender.sendMessage(error?.toString() ?: value) },
                        )
                }
            else -> sender.sendMessage("/lods cache status|trim")
        }
        return true
    }

    override fun close() {
        worker.shutdownNow()
        HandlerList.unregisterAll(this)
    }

    companion object {
        @JvmStatic
        fun bytes(mib: Long): Long {
            require(mib >= -1) { "Cache limit must be -1 or nonnegative MiB" }
            return if (mib == -1L) -1 else Math.multiplyExact(mib, 1_048_576L)
        }
    }
}
