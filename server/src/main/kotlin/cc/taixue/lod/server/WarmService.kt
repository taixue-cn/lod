package cc.taixue.lod.server

import cc.taixue.lod.core.CacheCoverage
import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.core.SavedChunks
import cc.taixue.lod.core.Wire
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent

/** Server-owned prewarming scans saved terrain; it never generates unexplored chunks. */
class WarmService(private val plugin: LodServer) : AutoCloseable, Listener {
    /** Main-thread snapshot; scanning does not invent an estimated total. */
    data class Status(
        val state: String,
        val completedChunks: Long,
        val failedChunks: Long,
        val totalChunks: Long? = null,
        val cachedChunks: Long = 0,
    )

    private data class Key(val world: UUID, val x: Int, val z: Int)

    private class Job(val world: World, val protocols: Set<String>, val region: Path) {
        val coverage = CacheCoverage()
        @Volatile var cancelled = false
        var scan: SavedChunks? = null
        var pending = false
        var finished = false
        var done = 0L
        var errors = 0L
        var regionX = Int.MIN_VALUE
        var regionZ = 0
        val sections = mutableSetOf<Long>()
    }

    private val reader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LOD-region-index").apply { isDaemon = true }
    }
    private val inventory = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LOD-coverage-inventory").apply { isDaemon = true }
    }
    private val coverage = java.util.concurrent.ConcurrentHashMap<UUID, CacheCoverage>()
    private val cacheListener: (ColumnCache.Key, Long) -> Unit = { key, version ->
        coverage[key.world]?.observe(Wire.pack(key.x, key.z), version)
    }
    private val jobs = linkedMapOf<UUID, Job>()
    private val unloads = linkedMapOf<Key, ChunkSnapshot?>()
    private val unloadDh = linkedSetOf<Key>()
    private var active = 0
    private var rotation = 0
    @Volatile private var closed = false
    private var completed = 0L
    private var failed = 0L

    init {
        plugin.voxyService.cache().listeners.add(cacheListener)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), 1, 1)
        Bukkit.getScheduler()
            .runTaskLater(plugin, Runnable { Bukkit.getWorlds().forEach(::auto) }, 40)
    }

    private fun settings(world: World): ConfigurationSection? {
        val values =
            plugin.config.getConfigurationSection("prewarm.by_world")?.getValues(false)
                ?: return null
        return (values[world.name] ?: values[world.uid.toString()]) as? ConfigurationSection
    }

    private fun protocols(world: World): Set<String> {
        val settings = settings(world)
        val list =
            if (settings?.contains("protocols") == true) settings.getStringList("protocols")
            else plugin.config.getStringList("prewarm.global.protocols")
        val result = list.ifEmpty { listOf("voxy", "dh") }.toSet()
        require(setOf("voxy", "dh").containsAll(result)) { "prewarm protocols: voxy, dh" }
        return result
    }

    private fun auto(world: World) {
        if (plugin.config.getBoolean("management.enabled", false)) return
        val settings = settings(world)
        val enabled =
            if (settings?.contains("enabled") == true) settings.getBoolean("enabled")
            else plugin.config.getBoolean("prewarm.global.enabled", false)
        if (enabled) start(world)
    }

    @EventHandler
    fun load(event: WorldLoadEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { auto(event.world) }, 20)
    }

    @EventHandler
    fun unloadWorld(event: WorldUnloadEvent) {
        jobs.remove(event.world.uid)?.cancelled = true
        coverage.remove(event.world.uid)
        unloads.keys.removeIf { it.world == event.world.uid }
        unloadDh.removeIf { it.world == event.world.uid }
    }

    fun reload() = Bukkit.getWorlds().forEach(::auto)

    fun finished(world: UUID): Boolean = jobs[world]?.finished == true

    fun idle(): Boolean = active == 0 && unloads.isEmpty() && unloadDh.isEmpty()

    fun start(world: World, requestedProtocols: Set<String>? = null): Boolean {
        if (!plugin.worldAccess.cacheAllowed(world.uid)) return false
        val old = jobs[world.uid]
        if (old != null && !old.finished) return false
        val selected = requestedProtocols ?: protocols(world)
        require(selected.isNotEmpty() && setOf("voxy", "dh").containsAll(selected))
        old?.cancelled = true
        val job = Job(world, selected, region(world))
        jobs[world.uid] = job
        coverage[world.uid] = job.coverage
        val worldId = world.uid
        val worldName = world.name
        inventory.execute {
            try {
                // Count only saved chunks accepted by the same reader as the prewarm queue.
                // This scan uses a separate I/O worker, so it cannot block generation dispatch.
                plugin.voxyService.cache().catalog().forEach { (key, version) ->
                    if (key.world == worldId)
                        job.coverage.observe(Wire.pack(key.x, key.z), version, seed = true)
                }
                val positions = mutableSetOf<Long>()
                val reader = SavedChunks(job.region)
                while (!closed && !job.cancelled && !Thread.currentThread().isInterrupted) {
                    val chunk = reader.next() ?: break
                    positions += Wire.pack(chunk.x, chunk.z)
                }
                if (!closed && !job.cancelled) job.coverage.inventory(positions)
            } catch (error: Exception) {
                if (!closed && !job.cancelled)
                    plugin.logger.warning(
                        "LOD coverage inventory ${worldName}: ${error.javaClass.simpleName}"
                    )
            }
        }
        return true
    }

    fun stop(world: UUID): Boolean {
        unloads.keys.removeIf { it.world == world }
        unloadDh.removeIf { it.world == world }
        coverage.remove(world)
        return jobs.remove(world)?.also { it.cancelled = true } != null
    }

    fun status(world: UUID): Status {
        val job = jobs[world] ?: return Status("OFF", 0, 0)
        val state = if (!job.finished) "RUNNING" else if (job.errors > 0) "FAILED" else "COMPLETED"
        return Status(
            state,
            job.done,
            job.errors,
            job.coverage.totalChunks,
            job.coverage.cachedChunks,
        )
    }

    private fun region(world: World): Path {
        val root = world.worldFolder.toPath()
        if (Files.isDirectory(root.resolve("region"))) return root.resolve("region")
        return root
            .resolve(if (world.environment == World.Environment.NETHER) "DIM-1" else "DIM1")
            .resolve("region")
    }

    private fun main(operation: () -> Unit) {
        if (!closed && plugin.isEnabled)
            Bukkit.getScheduler().runTask(plugin, Runnable { if (!closed) operation() })
    }

    private fun tick() {
        if (closed) return
        var budget = plugin.config.getInt("prewarm.global.chunks-per-tick", 1).coerceIn(1, 8)
        while (budget > 0 && active < 2 && unloads.isNotEmpty()) {
            val iterator = unloads.iterator()
            val (key, snapshot) = iterator.next()
            iterator.remove()
            val world = Bukkit.getWorld(key.world) ?: continue
            if (!plugin.worldAccess.cacheAllowed(key.world)) continue
            active++
            budget--
            val future =
                if (snapshot == null) plugin.voxyService.warm(world, key.x, key.z)
                else plugin.voxyService.refreshSnapshot(world, snapshot)
            future.whenComplete { _, error ->
                main {
                    active--
                    if (error != null) {
                        failed++
                        plugin.logger.warning("Unload Voxy refresh: $error")
                    } else completed++
                }
            }
        }
        if (budget > 0 && active < 2 && unloadDh.isNotEmpty()) {
            val iterator = unloadDh.iterator()
            val key = iterator.next()
            iterator.remove()
            if (plugin.worldAccess.cacheAllowed(key.world)) {
                active++
                budget--
                plugin.dhSupport.refreshLod(key.world, key.x, key.z).whenComplete { _, error ->
                    main {
                        active--
                        if (error != null) failed++ else completed++
                    }
                }
            }
        }
        val order = jobs.values.toMutableList()
        if (order.isNotEmpty()) Collections.rotate(order, -(rotation++ % order.size))
        for (job in order) {
            if (active >= 2 || budget <= 0) break
            if (job.pending || job.finished) continue
            if (!plugin.worldAccess.cacheAllowed(job.world.uid)) {
                job.finished = true
                continue
            }
            job.pending = true
            active++
            budget--
            reader.execute {
                try {
                    val scan = job.scan ?: SavedChunks(job.region).also { job.scan = it }
                    val next = scan.next()
                    main { dispatch(job, next) }
                } catch (error: Exception) {
                    main {
                        job.pending = false
                        job.finished = true
                        job.errors++
                        active--
                        plugin.logger.warning("Prewarm scan ${job.world.name}: $error")
                    }
                }
            }
        }
    }

    private fun dispatch(job: Job, next: SavedChunks.Chunk?) {
        if (jobs[job.world.uid] !== job || !plugin.worldAccess.cacheAllowed(job.world.uid)) {
            active--
            job.pending = false
            job.finished = true
            return
        }
        if (next == null) {
            active--
            job.pending = false
            job.finished = true
            plugin.logger.info(
                "Prewarm complete ${job.world.name}: chunks=${job.done}, failures=${job.errors}"
            )
            return
        }
        if (!job.world.isChunkGenerated(next.x, next.z)) {
            active--
            job.pending = false
            return
        }
        val tasks = mutableListOf<CompletableFuture<*>>()
        if ("voxy" in job.protocols) tasks += plugin.voxyService.warm(job.world, next.x, next.z)
        if (job.regionX != next.regionX || job.regionZ != next.regionZ) {
            job.sections.clear()
            job.regionX = next.regionX
            job.regionZ = next.regionZ
        }
        val x = Math.floorDiv(next.x, 4)
        val z = Math.floorDiv(next.z, 4)
        if ("dh" in job.protocols && job.sections.add(Wire.pack(x, z)))
            tasks += plugin.dhSupport.refreshLod(job.world.uid, x, z)
        CompletableFuture.allOf(*tasks.toTypedArray()).whenComplete { _, error ->
            main {
                active--
                job.pending = false
                job.done++
                if (error != null) {
                    job.errors++
                    plugin.logger.warning("Prewarm ${job.world.name}: $error")
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun unload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        val world = chunk.world
        if (BackgroundChunks.unloaded(world.uid, chunk.x, chunk.z)) return
        if (
            !plugin.config.getBoolean("cache.refresh-on-unload", true) ||
                !plugin.worldAccess.cacheAllowed(world.uid)
        )
            return
        // Beyond the snapshot cap, retain coordinates and read the saved chunk after unload.
        unloads[Key(world.uid, chunk.x, chunk.z)] =
            if (unloads.size < 256) chunk.getChunkSnapshot(true, true, false) else null
        unloadDh += Key(world.uid, Math.floorDiv(chunk.x, 4), Math.floorDiv(chunk.z, 4))
    }

    fun command(sender: CommandSender, args: Array<String>): Boolean {
        val action = args.firstOrNull() ?: "status"
        val target = args.getOrNull(1) ?: "all"
        if (action == "status") {
            sender.sendMessage(
                "prewarm active=$active, unload queue=${unloads.size}/${unloadDh.size}, refreshed=$completed, failed=$failed"
            )
            jobs.values.forEach {
                sender.sendMessage(
                    "${it.world.name}: ${if (it.finished) "finished" else "running"}, chunks=${it.done}, failures=${it.errors}"
                )
            }
            return true
        }
        if (action !in setOf("start", "stop")) {
            sender.sendMessage("/lods warm start|stop|status [world|all]")
            return true
        }
        var changed = 0
        for (world in Bukkit.getWorlds()) {
            if (target !in setOf("all", world.name, world.uid.toString())) continue
            if (action == "start") {
                if (start(world)) changed++
            } else if (stop(world.uid)) changed++
        }
        sender.sendMessage("Prewarm $action: $changed worlds")
        return true
    }

    override fun close() {
        closed = true
        jobs.values.forEach { it.cancelled = true }
        inventory.shutdownNow()
        plugin.voxyService.cache().listeners.remove(cacheListener)
        coverage.clear()
        reader.shutdownNow()
        jobs.clear()
        unloads.clear()
        unloadDh.clear()
        BackgroundChunks.clear()
        HandlerList.unregisterAll(this)
    }
}
