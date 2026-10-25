package cc.taixue.lod.server

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

/** One capacity budget spans Voxy, native records and DH payloads without quota eviction. */
class CacheManager(private val plugin: LodServer) : AutoCloseable, Listener {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LOD-cache-retention").apply { isDaemon = true }
    }
    private val worlds = Properties()
    @Volatile private var rules = emptyMap<UUID, Long>()
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
                        plugin.cacheQuota.pauseReason() != null &&
                            System.currentTimeMillis() - lastRun >=
                                maxOf(1, plugin.config.getInt("cache.maintenance-seconds", 30)) *
                                    1000L
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
        val snapshot = mutableMapOf<UUID, Long>()
        plugin.config.getConfigurationSection("cache.by_world")?.getValues(false)?.forEach {
            (name, value) ->
            if (value is ConfigurationSection) {
                for (id in worlds.stringPropertyNames()) {
                    if (name == id || name == worlds.getProperty(id)) {
                        snapshot[UUID.fromString(id)] =
                            if (value.contains("max-mib")) bytes(value.getLong("max-mib")) else -1
                    }
                }
            }
        }
        rules = snapshot.toMap()
        plugin.cacheQuota.configure(global, rules)
        lastRun = 0
    }

    /** Recount externally freed space; this command never deletes data to make quota room. */
    @Synchronized
    fun trim(): CompletableFuture<String> {
        if (running) return checkNotNull(current)
        running = true
        lastRun = System.currentTimeMillis()
        val result = CompletableFuture<String>()
        current = result
        worker.execute {
            try {
                while (!plugin.cacheQuota.beginInventory()) Thread.sleep(10)
                val sizes = mutableMapOf<UUID, Long>()
                var visited = 0
                for ((directory, suffix) in
                    listOf("voxy-cache" to ".gz", "voxy-native-v1" to ".native")) {
                    val root = plugin.dataFolder.toPath().resolve(directory)
                    if (!Files.isDirectory(root)) continue
                    Files.walk(root).use { paths ->
                        val iterator = paths.iterator()
                        while (iterator.hasNext()) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val path = iterator.next()
                            if (!path.fileName.toString().endsWith(suffix)) continue
                            val world =
                                runCatching { UUID.fromString(path.parent.fileName.toString()) }
                                    .getOrNull() ?: continue
                            try {
                                sizes.merge(world, Files.size(path), Long::plus)
                            } catch (_: java.nio.file.NoSuchFileException) {}
                            if (++visited % 256 == 0) Thread.sleep(1)
                        }
                    }
                }
                plugin.dhSupport.lodRepository.cacheUsageAsync().join().forEach { (world, bytes) ->
                    sizes.merge(world, bytes, Long::plus)
                }
                plugin.cacheQuota.initialize(sizes)
                status = plugin.cacheQuota.status()
                result.complete(status)
            } catch (error: Exception) {
                status = "cache inventory failed; generation paused: $error"
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
            "status" -> sender.sendMessage(plugin.cacheQuota.status())
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
