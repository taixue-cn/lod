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

    private data class Work(
        val chunk: SavedChunks.Chunk,
        val voxy: Boolean,
        val dh: Boolean,
        val verifiedFull: Boolean = false,
    )

    private class Job(val world: World, val protocols: Set<String>, val region: Path) {
        val coverage = CacheCoverage()
        @Volatile var cancelled = false
        var scan: SavedChunks? = null
        var scanning = false
        var exhausted = false
        var pending = 0
        var reused = 0L
        val ready = ArrayDeque<Work>()
        var finished = false
        var done = 0L
        var errors = 0L
        var regionX = Int.MIN_VALUE
        var regionZ = 0
        val sections = mutableSetOf<Long>()
        val storedSections = mutableMapOf<Long, Boolean>()
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
    private val budget = PrewarmBudget()
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
                val slice = budget.reader { closed || job.cancelled }
                // Count the same saved chunks as prewarming; background CPU/allocations still
                // need a budget even though this worker never blocks the dispatch queue.
                slice.checkpoint()
                plugin.voxyService.cache().catalog(worldId).forEach { (key, version) ->
                    slice.checkpoint()
                    if (key.world == worldId)
                        job.coverage.observe(Wire.pack(key.x, key.z), version, seed = true)
                }
                val positions = mutableSetOf<Long>()
                val reader = SavedChunks(job.region)
                while (!closed && !job.cancelled && !Thread.currentThread().isInterrupted) {
                    val chunk =
                        reader.next {
                            slice.checkpoint()
                            true
                        } ?: break
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
        if (!closed && plugin.isEnabled) {
            if (Bukkit.isPrimaryThread()) operation()
            else Bukkit.getScheduler().runTask(plugin, Runnable { if (!closed) operation() })
        }
    }

    private fun tick() {
        if (closed) return
        budget.tick(
            plugin.config.getDouble("prewarm.global.min-tps", 19.5),
            Bukkit.getServer().averageTickTime,
            plugin.config.getDouble("prewarm.global.max-mspt", 40.0),
            plugin.config.getInt("prewarm.global.scan-duty-percent", 25),
        )
        if (budget.paused) return
        val capacity = plugin.config.getInt("prewarm.global.max-inflight", 32).coerceIn(4, 256)
        while (active < capacity && unloads.isNotEmpty()) {
            val iterator = unloads.iterator()
            val (key, snapshot) = iterator.next()
            iterator.remove()
            val world = Bukkit.getWorld(key.world) ?: continue
            if (!plugin.worldAccess.cacheAllowed(key.world)) continue
            active++
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
        if (active < capacity && unloadDh.isNotEmpty()) {
            val iterator = unloadDh.iterator()
            val key = iterator.next()
            iterator.remove()
            if (plugin.worldAccess.cacheAllowed(key.world)) {
                active++
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
        // Bound snapshots/chunk loads, rather than leaving the encoder idle behind a tick quota.
        // Two slots remain available to real-time refreshes even with a full initialization queue.
        val deadline =
            System.nanoTime() +
                plugin.config.getLong("prewarm.global.main-thread-millis", 4).coerceIn(1, 25) *
                    1_000_000
        for (job in order) {
            if (job.finished || job.cancelled) continue
            if (!plugin.worldAccess.cacheAllowed(job.world.uid)) {
                job.cancelled = true
                job.ready.clear()
                job.finished = true
                continue
            }
            if (!job.scanning && !job.exhausted && job.ready.size < 32) scan(job)
        }
        val perWorld = plugin.config.getInt("prewarm.global.world-max-inflight", 8).coerceIn(1, 32)
        var dispatched: Boolean
        do {
            dispatched = false
            for (job in order) {
                if (active >= capacity - 2 || System.nanoTime() >= deadline) return
                if (job.cancelled || job.pending >= perWorld || job.ready.isEmpty()) continue
                dispatch(job, job.ready.removeFirst())
                dispatched = true
            }
        } while (dispatched)
    }

    private fun scan(job: Job) {
        job.scanning = true
        val worldId = job.world.uid
        reader.execute {
            try {
                val slice = budget.reader { closed || job.cancelled }
                val scan = job.scan ?: SavedChunks(job.region).also { job.scan = it }
                val batch = mutableListOf<Work>()
                var reused = 0L
                var exhausted = false
                var visited = 0
                while (
                    !closed &&
                        !job.cancelled &&
                        batch.size < 32 &&
                        visited++ < 1024 &&
                        reused < 1024
                ) {
                    var candidate: Work? = null
                    val next = scan.next { chunk ->
                        slice.checkpoint()
                        if (closed || job.cancelled || Thread.currentThread().isInterrupted)
                            throw InterruptedException()
                        if (job.regionX != chunk.regionX || job.regionZ != chunk.regionZ) {
                            job.sections.clear()
                            job.storedSections.clear()
                            job.regionX = chunk.regionX
                            job.regionZ = chunk.regionZ
                        }
                        val voxy =
                            "voxy" in job.protocols &&
                                !plugin.voxyService.hasCached(
                                    ColumnCache.Key(worldId, chunk.x, chunk.z)
                                )
                        val x = Math.floorDiv(chunk.x, 4)
                        val z = Math.floorDiv(chunk.z, 4)
                        val section = Wire.pack(x, z)
                        val dh =
                            "dh" in job.protocols &&
                                section !in job.sections &&
                                !job.storedSections.getOrPut(section) {
                                    plugin.dhSupport.lodRepository
                                        .lodExistsAsync(worldId, x, z)
                                        .join()
                                }
                        candidate = Work(chunk, voxy, dh)
                        if (!voxy && !dh) reused++
                        // Yield between worlds after a bounded run of cached slots, without reading
                        // their NBT.
                        voxy || dh || reused >= 1024
                    }
                    if (next == null) {
                        exhausted = true
                        break
                    }
                    val work = checkNotNull(candidate)
                    if (work.dh)
                        job.sections +=
                            Wire.pack(Math.floorDiv(next.x, 4), Math.floorDiv(next.z, 4))
                    if (work.voxy || work.dh)
                        batch += work.copy(verifiedFull = scan.lastVerifiedFull)
                }
                main {
                    job.scanning = false
                    if (jobs[worldId] !== job || job.cancelled) return@main
                    job.reused += reused
                    job.ready.addAll(batch)
                    job.exhausted = exhausted
                    finish(job)
                }
            } catch (error: Exception) {
                main {
                    job.scanning = false
                    job.exhausted = true
                    job.errors++
                    plugin.logger.warning("Prewarm scan ${job.world.name}: $error")
                    finish(job)
                }
            }
        }
    }

    private fun finish(job: Job) {
        if (
            !job.finished &&
                job.exhausted &&
                !job.scanning &&
                job.pending == 0 &&
                job.ready.isEmpty()
        ) {
            job.finished = true
            plugin.logger.info(
                "Prewarm complete ${job.world.name}: chunks=${job.done}, reused=${job.reused}, failures=${job.errors}"
            )
        }
    }

    private fun dispatch(job: Job, work: Work) {
        val next = work.chunk
        if (
            !plugin.worldAccess.cacheAllowed(job.world.uid) ||
                (!work.verifiedFull && !job.world.isChunkGenerated(next.x, next.z))
        ) {
            finish(job)
            return
        }
        active++
        job.pending++
        val tasks = mutableListOf<CompletableFuture<*>>()
        if (work.voxy)
            tasks +=
                plugin.voxyService.warm(
                    job.world,
                    next.x,
                    next.z,
                    reuseCached = true,
                    knownGenerated = work.verifiedFull,
                )
        if (work.dh) {
            val position = no.jckf.dhsupport.core.dataobject.SectionPosition()
            position.detailLevel = 6
            position.x = Math.floorDiv(next.x, 4)
            position.z = Math.floorDiv(next.z, 4)
            tasks += plugin.dhSupport.getLod(job.world.uid, position)
        }
        CompletableFuture.allOf(*tasks.toTypedArray()).whenComplete { _, error ->
            main {
                active--
                job.pending--
                job.done++
                if (error != null) {
                    job.errors++
                    plugin.logger.warning("Prewarm ${job.world.name}: $error")
                }
                finish(job)
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
            if (!budget.paused && unloads.size < 256) chunk.getChunkSnapshot(true, true, false)
            else null
        unloadDh += Key(world.uid, Math.floorDiv(chunk.x, 4), Math.floorDiv(chunk.z, 4))
    }

    fun command(sender: CommandSender, args: Array<String>): Boolean {
        val action = args.firstOrNull() ?: "status"
        val target = args.getOrNull(1) ?: "all"
        if (action == "budget") {
            val millis = args.getOrNull(1)?.toIntOrNull()
            if (millis == null || millis !in 1..25)
                sender.sendMessage("/lod warm budget <1..25> (milliseconds per tick)")
            else {
                plugin.config.set("prewarm.global.main-thread-millis", millis)
                sender.sendMessage("Prewarm main-thread budget: $millis ms/tick (temporary)")
            }
            return true
        }
        if (action == "limit") {
            val limit = args.getOrNull(1)?.toIntOrNull()
            if (limit == null || limit !in 4..256) {
                sender.sendMessage("/lod warm limit <4..256>")
            } else {
                // Session-only tuning: avoid rewriting administrator policy or restarting jobs.
                plugin.config.set("prewarm.global.max-inflight", limit)
                sender.sendMessage(
                    "Prewarm in-flight limit: $limit (temporary; restart restores config)"
                )
            }
            return true
        }
        if (action == "status") {
            sender.sendMessage(
                "prewarm active=$active, paused=${budget.paused}, measured-tps=${"%.2f".format(java.util.Locale.ROOT, budget.tps)}, limit=${plugin.config.getInt("prewarm.global.max-inflight", 32)}, unload queue=${unloads.size}/${unloadDh.size}, refreshed=$completed, failed=$failed"
            )
            jobs.values.forEach {
                sender.sendMessage(
                    "${it.world.name}: ${if (it.finished) "finished" else "running"}, chunks=${it.done}, failures=${it.errors}, reused=${it.reused}, queued=${it.ready.size}, inflight=${it.pending}"
                )
            }
            return true
        }
        if (action !in setOf("start", "stop")) {
            sender.sendMessage("/lods warm start|stop|status [world|all]; /lod warm limit <4..256>")
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
