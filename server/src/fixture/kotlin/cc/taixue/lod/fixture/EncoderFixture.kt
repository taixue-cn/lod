package cc.taixue.lod.fixture

import cc.taixue.lod.core.ColumnValidation
import cc.taixue.lod.server.ColumnEncoder
import cc.taixue.lod.server.SnapshotStates
import java.nio.file.Files
import java.nio.file.Path
import org.bukkit.ChunkSnapshot

/** Semantic and timed comparisons against the original encoder on identical immutable snapshots. */
object EncoderFixture {
    fun verify(snapshots: List<ChunkSnapshot>, minimum: Int, maximum: Int) {
        // Require the optimization itself to work; silently falling back cannot pass this check.
        val type = Class.forName("cc.taixue.lod.server.PaletteSnapshotEncoder")
        val encoder =
            type.getDeclaredConstructor(Class::class.java).newInstance(snapshots.first().javaClass)
        val encode =
            type.getMethod(
                "encode",
                ChunkSnapshot::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        for (snapshot in snapshots) {
            val old =
                ColumnValidation.validate(
                    ColumnEncoder.encodeUsingApi(snapshot, minimum, maximum),
                    minimum / 16,
                    maximum / 16,
                )
            val actual =
                ColumnValidation.validate(
                    encode.invoke(encoder, snapshot, minimum, maximum) as ByteArray,
                    minimum / 16,
                    maximum / 16,
                )
            check(old.sections().size == actual.sections().size)
            for ((left, right) in old.sections().zip(actual.sections())) {
                check(left.sectionY() == right.sectionY())
                check(left.nonEmptyBlockCount() == right.nonEmptyBlockCount())
                check(left.fluidCount() == right.fluidCount())
                val referenceStates = ColumnValidation.values(left.blocks(), 4096)
                for (index in 0 until 4096 step 17) {
                    val x = index and 15
                    val y = left.sectionY() * 16 + (index shr 8)
                    val z = (index shr 4) and 15
                    val identity = old.dictionary()[referenceStates[index]]
                    check(SnapshotStates.identity(snapshot, x, y, z) == identity)
                    val bracket = identity.indexOf('[')
                    val properties =
                        if (bracket < 0) emptyMap()
                        else
                            identity
                                .substring(bracket + 1, identity.length - 1)
                                .split(',')
                                .associate { property ->
                                    val pair = property.split('=', limit = 2)
                                    pair[0] to pair[1]
                                }
                    check(SnapshotStates.properties(snapshot, x, y, z) == properties)
                }
                check(left.blockLight().contentEquals(right.blockLight()))
                check(left.skyLight().contentEquals(right.skyLight()))
                for ((a, b, size) in
                    listOf(
                        Triple(left.blocks(), right.blocks(), 4096),
                        Triple(left.biomes(), right.biomes(), 64),
                    )) {
                    val before = ColumnValidation.values(a, size)
                    val after = ColumnValidation.values(b, size)
                    check(
                        before.indices.all {
                            old.dictionary()[before[it]] == actual.dictionary()[after[it]]
                        }
                    )
                }
            }
        }
        repeat(3) {
            snapshots.forEach {
                ColumnEncoder.encode(it, minimum, maximum)
                ColumnEncoder.encodeUsingApi(it, minimum, maximum)
            }
        }
        fun measure(fast: Boolean, rounds: Int): Double {
            val start = System.nanoTime()
            repeat(rounds) {
                snapshots.forEach {
                    if (fast) ColumnEncoder.encode(it, minimum, maximum)
                    else ColumnEncoder.encodeUsingApi(it, minimum, maximum)
                }
            }
            return (System.nanoTime() - start).toDouble() / rounds / snapshots.size / 1_000_000
        }
        val baseline = (0 until 3).minOf { measure(false, 10) }
        val optimized = (0 until 3).minOf { measure(true, 50) }
        Files.writeString(
            Path.of("encoder-benchmark.json"),
            """{"status":"PASS","snapshots":${snapshots.size},"api_ms_per_column":$baseline,"palette_ms_per_column":$optimized,"speedup":${baseline/optimized},"semantic_equality":true}""",
        )
    }
}
