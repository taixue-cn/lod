package cc.taixue.lod.fixture

import cc.taixue.lod.server.SnapshotStates
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import no.jckf.dhsupport.core.bytestream.Encoder
import no.jckf.dhsupport.core.configuration.Configuration
import no.jckf.dhsupport.core.configuration.DhsConfig
import no.jckf.dhsupport.core.dataobject.Lod
import no.jckf.dhsupport.core.dataobject.SectionPosition
import no.jckf.dhsupport.core.lodbuilders.FullBuilder
import no.jckf.dhsupport.core.world.WorldInterface
import org.bukkit.ChunkSnapshot
import org.bukkit.Material

/** Runs the actual DH builder against identical snapshots, comparing its complete wire payload. */
object DhRunFixture {
    @Volatile private var sink: Lod? = null

    fun verify(snapshots: List<ChunkSnapshot>, minimum: Int, maximum: Int) {
        fun build(
            snapshot: ChunkSnapshot,
            fast: Boolean,
            biomes3d: Boolean,
            underglow: Boolean,
        ): Lod {
            val config = Configuration()
            config.set(DhsConfig.SAMPLE_BIOMES_3D, biomes3d)
            config.set(DhsConfig.PERFORM_UNDERGLOW_HACK, underglow)
            val world =
                Proxy.newProxyInstance(
                    WorldInterface::class.java.classLoader,
                    arrayOf(WorldInterface::class.java),
                ) { _, method, args ->
                    val x = (args?.getOrNull(0) as? Int ?: 0) and 15
                    val y = args?.getOrNull(1) as? Int ?: 0
                    val z = (args?.getOrNull(2) as? Int ?: 0) and 15
                    when (method.name) {
                        "getConfig" -> config
                        "getMinY" -> minimum
                        "getMaxY" -> maximum
                        "getHighestYAt" -> snapshot.getHighestBlockYAt(x, y and 15)
                        "getBiomeAt" ->
                            snapshot
                                .getBiome(
                                    x,
                                    if (args!!.size == 3) y else minimum,
                                    if (args.size == 3) z else y and 15,
                                )
                                .key
                                .toString()
                        "getMaterialAt" -> snapshot.getBlockType(x, y, z).key.toString()
                        "getBlockStateAsStringAt" -> SnapshotStates.identity(snapshot, x, y, z)
                        "getBlockPropertiesAt" -> SnapshotStates.properties(snapshot, x, y, z)
                        "getBlockLightAt" ->
                            snapshot
                                .getBlockEmittedLight(x, y.coerceIn(minimum, maximum - 1), z)
                                .toByte()
                        "getSkyLightAt" ->
                            snapshot
                                .getBlockSkyLight(x, y.coerceIn(minimum, maximum - 1), z)
                                .toByte()
                        "isBeacon" -> snapshot.getBlockType(x, y, z) == Material.BEACON
                        "getBeaconColor" -> 0x00FF00
                        "getIdenticalRunLength" ->
                            if (fast) SnapshotStates.runLength(snapshot, x, y, z, minimum) else 1
                        else -> error("Unhandled DH fixture method: ${method.name}")
                    }
                } as WorldInterface
            val position =
                SectionPosition().apply {
                    detailLevel = 6
                    x = 0
                    z = 0
                }
            return FullBuilder(world, position, fast).generate()
        }
        fun payload(lod: Lod): ByteArray {
            val encoder = Encoder()
            lod.encode(encoder)
            val data = encoder.toByteArray()
            // DH appends two wall-clock timestamps; all model bytes must remain identical.
            return data.copyOf(data.size - 16)
        }
        fun beacons(lod: Lod): ByteArray {
            val encoder = Encoder()
            encoder.writeCollection(lod.beacons)
            return encoder.toByteArray()
        }
        for (snapshot in snapshots) for (sample3d in listOf(false, true)) for (underglow in
            listOf(false, true)) {
            val reference = build(snapshot, false, sample3d, underglow)
            val actual = build(snapshot, true, sample3d, underglow)
            check(payload(reference).contentEquals(payload(actual))) {
                "DH vertical runs changed model payload"
            }
            check(beacons(reference).contentEquals(beacons(actual))) {
                "DH vertical runs changed beacons"
            }
        }
        fun measure(fast: Boolean): Double {
            val start = System.nanoTime()
            snapshots.forEach { sink = build(it, fast, false, false) }
            return (System.nanoTime() - start).toDouble() / snapshots.size / 1_000_000
        }
        measure(false)
        measure(true)
        val reference = (0 until 3).minOf { measure(false) }
        val actual = (0 until 3).minOf { measure(true) }
        Files.writeString(
            Path.of("dh-run-benchmark.json"),
            """{"status":"PASS","snapshots":${snapshots.size},"minimum":$minimum,"maximum":$maximum,"reference_ms":$reference,"run_ms":$actual,"speedup":${reference/actual},"wire_equality":true,"beacon_equality":true,"biome3d_and_underglow":true}""",
        )
    }
}
