package cc.taixue.lod.fixture

import cc.taixue.lod.core.CacheRetention
import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.server.BackgroundChunks
import cc.taixue.lod.server.LodServer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkPopulateEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.java.JavaPlugin

/** Actual Paper, SQLite and filesystem checks; excluded from release artifacts. */
class CacheFixture(private val fixture: JavaPlugin, private val lod: LodServer) : Listener {
    private val passed = mutableListOf<String>()
    private val target = mutableSetOf<UUID>()
    private val generated = AtomicInteger()
    private val unloaded = AtomicInteger()

    private fun <T> main(operation: () -> T): T {
        val result = CompletableFuture<T>()
        Bukkit.getScheduler()
            .runTask(
                fixture,
                Runnable {
                    try {
                        result.complete(operation())
                    } catch (error: Throwable) {
                        result.completeExceptionally(error)
                    }
                },
            )
        return result.get(60, TimeUnit.SECONDS)
    }

    private fun check(value: Boolean, name: String) {
        if (!value) throw AssertionError(name)
        passed += name
        fixture.logger.info("CACHE_PASS $name")
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("condition timed out")
    }

    private fun entries(): List<CacheRetention.Entry> =
        lod.voxyService.cache().entries() +
            lod.dhSupport.lodRepository.cacheEntriesAsync().get(30, TimeUnit.SECONDS)

    private fun entries(world: UUID) = entries().filter { it.world == world }

    private fun config(global: Long, perWorld: Map<String, Map<String, Any>>) {
        main {
            lod.config.set("cache.global.max-mib", global)
            lod.config.set("cache.by_world", null)
            perWorld.forEach { (world, settings) ->
                lod.config.createSection("cache.by_world.$world", settings)
            }
            lod.cacheManager.reload()
        }
    }

    private fun trim() {
        main { lod.cacheManager.trim() }.get(30, TimeUnit.SECONDS)
    }

    private fun populate(world: World) {
        main {
                CompletableFuture.allOf(
                    lod.voxyService.warm(world, 4, 4),
                    lod.dhSupport.refreshLod(world.uid, 1, 1),
                )
            }
            .get(30, TimeUnit.SECONDS)
    }

    private fun mapping(data: ByteArray): String {
        val buffer = ByteBuffer.wrap(data)
        buffer.position(12)
        repeat(7) {
            val size = buffer.int
            buffer.position(buffer.position() + size)
        }
        val compressed = ByteArray(buffer.int).also(buffer::get)
        val input =
            Class.forName("cc.taixue.lod.lib.xz.XZInputStream")
                .getConstructor(InputStream::class.java)
                .newInstance(ByteArrayInputStream(compressed)) as InputStream
        return input.use { it.readAllBytes().toString(Charsets.ISO_8859_1) }
    }

    @EventHandler
    fun generated(event: ChunkPopulateEvent) {
        if (event.world.uid in target) generated.incrementAndGet()
    }

    @EventHandler
    fun unloaded(event: ChunkUnloadEvent) {
        if (event.world.uid in target && event.chunk.x == 4 && event.chunk.z == 4)
            unloaded.incrementAndGet()
    }

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, fixture)
        Thread(
                {
                    try {
                        run()
                        write("PASS", "")
                    } catch (error: Throwable) {
                        fixture.logger.warning("CACHE_FAIL $error")
                        error.printStackTrace()
                        write("FAIL", error.toString())
                    } finally {
                        HandlerList.unregisterAll(this)
                    }
                },
                "LOD-cache-fixture",
            )
            .apply {
                isDaemon = true
                start()
            }
    }

    private class TinyGenerator : ChunkGenerator() {
        override fun getFixedSpawnLocation(world: World, random: Random) =
            Location(world, 0.0, 64.0, 0.0)

        override fun generateChunkData(
            world: World,
            random: Random,
            x: Int,
            z: Int,
            biomes: BiomeGrid,
        ): ChunkData = createChunkData(world).apply { setBlock(0, 63, 0, Material.STONE) }
    }

    private fun run() {
        val first = main {
            WorldCreator("cache-a").generator(TinyGenerator()).createWorld()!!
        }
        val second = main {
            WorldCreator("cache-b").generator(TinyGenerator()).createWorld()!!
        }
        main {
            lod.config.set("cache.refresh-on-unload", false)
            lod.config.set("cache.maintenance-seconds", 3600)
            lod.config.set("lod.worlds.mode", "blacklist")
            lod.config.set("lod.worlds.entries", emptyList<String>())
            lod.worldAccess.refreshPolicy()
            for (world in listOf(first, second)) {
                disableSpawnChunks(world)
                world.getBlockAt(64, 64, 64).type = Material.GOLD_BLOCK
                world.save()
                target += world.uid
            }
        }
        val uniform = main { first.getChunkAt(4, 4).getChunkSnapshot(true, true, false) }
        val mixed = main {
            val materials = Material.values().filter { it.isBlock && !it.isAir }.take(600)
            materials.forEachIndexed { index, material ->
                first
                    .getBlockAt(64 + (index and 15), index / 256, 64 + ((index shr 4) and 15))
                    .setType(material, false)
            }
            for (y in 16..31) first.getBlockAt(66, y, 66).setType(Material.STONE, false)
            for (y in 20..22) first.getBlockAt(67, y, 67).setType(Material.BEACON, false)
            first.getChunkAt(4, 4).getChunkSnapshot(true, true, false)
        }
        val thick = main {
            for (x in 64..79) for (z in 64..79) for (y in first.minHeight + 16..128) first
                .getBlockAt(x, y, z)
                .setType(Material.STONE, false)
            first.getBlockAt(64, 64, 64).setType(Material.GOLD_BLOCK, false)
            first.getChunkAt(4, 4).getChunkSnapshot(true, true, false)
        }
        DhRunFixture.verify(listOf(uniform, mixed, thick), first.minHeight, first.maxHeight)
        check(true, "DH runs preserve complete model bytes, beacons, 3D biomes and underglow")
        EncoderFixture.verify(listOf(uniform, mixed), first.minHeight, first.maxHeight)
        check(
            true,
            "palette encoder matches reference for uniform and global-palette sections, lights, biomes and negative height",
        )
        main {
            repeat(600) { index ->
                first
                    .getBlockAt(64 + (index and 15), index / 256, 64 + ((index shr 4) and 15))
                    .setType(Material.AIR, false)
            }
            first.save()
        }
        config(-1, emptyMap())
        populate(first)
        populate(second)
        // Repeated initialization must reuse the persisted column without loading its world chunk.
        val key = ColumnCache.Key(first.uid, 4, 4)
        val original = lod.voxyService.cache().version(key)
        main { first.unloadChunk(4, 4, true) }
        main { lod.voxyService.warm(first, 4, 4, reuseCached = true) }.get(30, TimeUnit.SECONDS)
        check(
            lod.voxyService.cache().version(key) == original,
            "prewarm cache hit is never rewritten",
        )
        check(!main { first.isChunkLoaded(4, 4) }, "prewarm cache hit never loads the chunk")
        check(
            entries(first.uid).any { it.id.startsWith("dh:") } &&
                entries(first.uid).any { it.id.startsWith("voxy:") },
            "server builds both model formats, including partial sections",
        )
        config(-1, mapOf("cache-a" to mapOf("max-mib" to 0), "cache-b" to mapOf("max-mib" to -1)))
        trim()
        check(
            entries(first.uid).isEmpty() && entries(second.uid).size >= 2,
            "per-world limit removes Voxy and DH while explicit -1 protects another world",
        )
        config(0, mapOf("cache-b" to mapOf("max-mib" to -1)))
        trim()
        check(entries(second.uid).size >= 2, "pinned world survives global zero budget")
        config(-1, mapOf("cache-a" to mapOf("max-mib" to 0), "cache-b" to mapOf("max-mib" to 0)))
        trim()
        val bytes = ByteArray(700000).also { Random(1234).nextBytes(it) }
        for (world in listOf(first, second)) {
            lod.voxyService
                .cache()
                .put(
                    ColumnCache.Key(world.uid, 1000, 1000),
                    ColumnCache.Entry(System.currentTimeMillis(), bytes),
                )
            lod.dhSupport.lodRepository
                .saveLodAsync(world.uid, 250, 250, bytes, byteArrayOf(), 1)
                .get(30, TimeUnit.SECONDS)
        }
        config(
            2,
            mapOf("cache-a" to mapOf("priority" to -10), "cache-b" to mapOf("priority" to 10)),
        )
        trim()
        check(
            entries(first.uid).isEmpty() &&
                entries(second.uid).size == 2 &&
                entries().sumOf { it.bytes } <= 2097152,
            "global combined budget evicts lower priority world before higher priority",
        )
        config(-1, mapOf("cache-b" to mapOf("max-mib" to 1)))
        trim()
        check(
            entries(second.uid).sumOf { it.bytes } <= 1048576,
            "world-only rolling limit applies when global is unlimited",
        )
        config(-1, mapOf("cache-a" to mapOf("max-mib" to 0), "cache-b" to mapOf("max-mib" to 0)))
        trim()
        config(-1, emptyMap())
        main {
            lod.warmService.start(first)
            lod.config.set("prewarm.global.enabled", false)
            lod.config.set("prewarm.by_world.cache-b.enabled", true)
            lod.config.set("prewarm.by_world.cache-b.protocols", listOf("dh"))
            lod.warmService.reload()
        }
        eventually {
            main { lod.warmService.finished(first.uid) && lod.warmService.finished(second.uid) }
        }
        check(
            entries(first.uid).any { it.id.startsWith("voxy:") } &&
                entries(first.uid).any { it.id.startsWith("dh:") },
            "map-file prewarm fills both formats without players visiting world",
        )
        check(
            entries(second.uid).isNotEmpty() && entries(second.uid).all { it.id.startsWith("dh:") },
            "by-world prewarm overrides global disabled and protocol selection",
        )
        check(generated.get() == 0, "prewarm creates no new terrain chunks")
        val beforeRestart =
            lod.voxyService.cache().catalog().filter { it.first.world == first.uid }.toMap()
        val dhBeforeRestart =
            entries(first.uid)
                .filter { it.id.startsWith("dh:") }
                .associate { it.id to it.timestamp }
        main {
            lod.warmService.stop(first.uid)
            lod.warmService.start(first)
        }
        eventually { main { lod.warmService.finished(first.uid) } }
        check(
            lod.voxyService.cache().catalog().filter { it.first.world == first.uid }.toMap() ==
                beforeRestart &&
                entries(first.uid)
                    .filter { it.id.startsWith("dh:") }
                    .associate { it.id to it.timestamp } == dhBeforeRestart,
            "restarted prewarm reuses existing Voxy and DH models without rewriting",
        )
        val inherited = main {
            lod.config.set("prewarm.by_world.cache-b.enabled", null)
            lod.config.set("prewarm.global.enabled", true)
            for (name in listOf("world", "lod-secret", "cache-a")) lod.config.set(
                "prewarm.by_world.$name.enabled",
                false,
            )
            lod.warmService.reload()
            lod.warmService.finished(first.uid) && !lod.warmService.finished(second.uid)
        }
        check(
            inherited,
            "global prewarm enables inherited worlds while explicit world disable wins",
        )
        eventually { main { lod.warmService.finished(second.uid) } }
        val unloadBefore = unloaded.get()
        main {
            lod.config.set("cache.refresh-on-unload", true)
            first.getBlockAt(64, 64, 64).type = Material.EMERALD_BLOCK
            BackgroundChunks.clear()
            first.unloadChunkRequest(4, 4)
        }
        eventually { main { !first.isChunkLoaded(4, 4) || first.unloadChunk(4, 4, true) } }
        eventually {
            val hit = lod.voxyService.cache().get(ColumnCache.Key(first.uid, 4, 4))
            val dh =
                lod.dhSupport.lodRepository.loadLodAsync(first.uid, 1, 1).get(30, TimeUnit.SECONDS)
            hit != null &&
                hit.body.toString(Charsets.ISO_8859_1).contains("minecraft:emerald_block") &&
                dh != null &&
                mapping(dh.data).contains("minecraft:emerald_block")
        }
        check(
            unloaded.get() > unloadBefore,
            "real chunk unload refreshes both Voxy and DH to the latest block state",
        )
        eventually { main { lod.warmService.idle() } }
        val before = entries(first.uid).size
        Thread.sleep(1500)
        check(
            main { lod.warmService.idle() } && entries(first.uid).size == before,
            "background unloads do not recursively rewarm chunks",
        )
    }

    private fun write(status: String, error: String) {
        val checks = passed.joinToString(",") { "\"$it\"" }
        val safeError = error.replace('\\', '/').replace('"', '\'').replace('\n', ' ')
        Files.writeString(
            Path.of("cache-test.json"),
            """{"status":"$status","checks":[$checks],"error":"$safeError","new_terrain_chunks":${generated.get()}}""",
        )
    }
}
