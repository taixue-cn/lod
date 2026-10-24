package cc.taixue.lod.client

import cc.taixue.lod.core.PreparedColumn
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level

/** Imports portable, pre-mipped cells through Voxy's WorldUpdater without allocating MC chunks. */
internal class NativeVoxy(private val resolver: VoxyBridge) {
    private val identityClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier")
    private val engineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine")
    private val sectionClass = Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection")
    private val mapperClass = Class.forName("me.cortex.voxy.common.world.other.Mapper")

    init {
        check(engineClass.getField("MAX_LOD_LAYER").getInt(null) == 4) {
            "Unsupported Voxy mip layout"
        }
        val probe =
            mapperClass
                .getMethod(
                    "composeMappingId",
                    Byte::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(null, 1.toByte(), 2, 3) as Long
        check(probe == ((1L shl 56) or (2L shl 27) or (3L shl 47))) {
            "Unsupported Voxy cell layout"
        }
    }

    private val insert =
        Class.forName("me.cortex.voxy.common.world.WorldUpdater")
            .getMethod("insertUpdate", engineClass, sectionClass)
    private val mip =
        Class.forName("me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper")
            .getMethod("mipSection", sectionClass, mapperClass)
    private val getBlock = mapperClass.methods.single { it.name == "getIdForBlockState" }
    private val getBiome = mapperClass.methods.single { it.name == "getIdForBiome" }
    private val getOpacity =
        mapperClass.getMethod("getBlockStateOpacity", Int::class.javaPrimitiveType)
    private val acquire =
        engineClass.getMethod(
            "acquireIfExists",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    private val worldSectionClass = Class.forName("me.cortex.voxy.common.world.WorldSection")
    private val nonEmpty = worldSectionClass.getMethod("getNonEmptyBlockCount")
    private val release = worldSectionClass.getMethod("release")

    /** The engine ref is acquired on the client thread and released after the worker's last job. */
    fun session(level: ClientLevel): Session? {
        val identity =
            identityClass.getMethod("of", Level::class.java).invoke(null, level) ?: return null
        val engine = identityClass.getMethod("getOrCreateEngine").invoke(identity) ?: return null
        engineClass.getMethod("acquireRef").invoke(engine)
        try {
            return Session(level, engine)
        } catch (error: Exception) {
            engineClass.getMethod("releaseRef").invoke(engine)
            throw error
        }
    }

    inner class Session(private val level: ClientLevel, private val engine: Any) {
        val active = AtomicBoolean(true)
        private val mapper = engineClass.getMethod("getMapper").invoke(engine)
        private val registry = level.registryAccess().lookupOrThrow(Registries.BIOME)
        private val plains = registry.get(Identifier.parse("minecraft:plains")).orElseThrow()
        private val blocks = mutableMapOf<String, Pair<Int, Int>>()
        private val biomes = mutableMapOf<String, Int>()
        private val voxelSection = sectionClass.getMethod("createEmpty").invoke(null)
        private val data = sectionClass.getField("section").get(voxelSection) as LongArray
        private val position =
            sectionClass.getMethod(
                "setPosition",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        private val count = sectionClass.getField("lvl0NonAirCount")

        fun import(x: Int, z: Int, bytes: ByteArray, valid: () -> Boolean = { true }): Boolean {
            if (!active.get() || !valid()) return false
            val column = PreparedColumn.decode(bytes)
            val ids = IntArray(column.dictionary.size) { -1 }
            val biomeIds = IntArray(column.dictionary.size) { -1 }
            var matchingOpacity = true
            for (section in column.sections) for (cell in section.palette) {
                val block = PreparedColumn.block(cell)
                if (block != 0 && ids[block - 1] == -1) {
                    val identity = column.dictionary[block - 1]
                    val entry =
                        blocks.getOrPut(identity) {
                            val id = getBlock.invoke(mapper, resolver.resolve(identity)) as Int
                            id to (getOpacity.invoke(mapper, id) as Int)
                        }
                    require(entry.first in 0 until (1 shl 20))
                    ids[block - 1] = entry.first
                    matchingOpacity = matchingOpacity && entry.second == column.opacity[block - 1]
                }
                if (block != 0) {
                    val bio = PreparedColumn.biome(cell)
                    if (biomeIds[bio] == -1) {
                        biomeIds[bio] =
                            biomes.getOrPut(column.dictionary[bio]) {
                                getBiome.invoke(
                                    mapper,
                                    registry
                                        .get(Identifier.parse(column.dictionary[bio]))
                                        .orElse(plains),
                                ) as Int
                            }
                        require(biomeIds[bio] in 0..511)
                    }
                }
            }
            val required =
                column.sections
                    .filter { it.nonAir > 0 }
                    .flatMap { listOf(it.y - 1, it.y, it.y + 1) }
                    .toSet()
            for (section in column.sections) {
                if (!active.get() || !valid()) return false
                if (section.y !in level.minSectionY..level.maxSectionY) {
                    require(section.nonAir == 0) { "native terrain outside world height" }
                    continue
                }
                if (section.nonAir == 0 && section.y !in required) {
                    val existing =
                        acquire.invoke(
                            engine,
                            0,
                            Math.floorDiv(x, 2),
                            Math.floorDiv(section.y, 2),
                            Math.floorDiv(z, 2),
                        )
                    if (existing == null) continue
                    val hasGeometry =
                        try {
                            (nonEmpty.invoke(existing) as Int) > 0
                        } finally {
                            release.invoke(existing)
                        }
                    if (!hasGeometry) continue
                }
                val palette =
                    LongArray(section.palette.size) { index ->
                        val cell = section.palette[index]
                        val b = PreparedColumn.block(cell)
                        (PreparedColumn.light(cell).toLong() shl 56) or
                            (if (b == 0) 0
                            else
                                (ids[b - 1].toLong() shl 27) or
                                    (biomeIds[PreparedColumn.biome(cell)].toLong() shl 47))
                    }
                var offset = 0
                for (i in section.runs.indices step 2) {
                    val end = offset + section.runs[i]
                    Arrays.fill(data, offset, end, palette[section.runs[i + 1]])
                    offset = end
                }
                position.invoke(voxelSection, x, section.y, z)
                count.setInt(voxelSection, section.nonAir)
                // Different Minecraft versions may change opacity. Recompute only on that mismatch.
                if (!matchingOpacity) mip.invoke(null, voxelSection, mapper)
                insert.invoke(null, engine, voxelSection)
            }
            return active.get()
        }

        fun release() {
            engineClass.getMethod("releaseRef").invoke(engine)
        }
    }
}
