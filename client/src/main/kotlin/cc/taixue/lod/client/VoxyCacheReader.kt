package cc.taixue.lod.client

import dev.vox.lss.common.wire.IdentityCodec
import dev.vox.lss.common.wire.WireSectionCursor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/** Reads only the active world's level-zero cache; absent sections remain explicitly unknown. */
internal object VoxyCacheReader {
    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun read(level: ClientLevel, x: Int, z: Int): ByteArray? {
        val worldIdentifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier")
        val identity =
            worldIdentifier.getMethod("of", Level::class.java).invoke(null, level) ?: return null
        val engine = worldIdentifier.getMethod("getNullable").invoke(identity) ?: return null
        val engineClass = engine.javaClass
        if (!(engineClass.getMethod("isLive").invoke(engine) as Boolean)) return null
        engineClass.getMethod("acquireRef").invoke(engine)
        try {
            val mapper = engineClass.getMethod("getMapper").invoke(engine)
            val mapperClass = mapper.javaClass
            val acquire =
                engineClass.getMethod(
                    "acquireIfExists",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            val blockFor =
                mapperClass.getMethod("getBlockStateFromBlockId", Int::class.javaPrimitiveType)
            val biomes = mapperClass.getMethod("getBiomeEntries").invoke(mapper) as Array<*>
            val dictionary = linkedMapOf<String, Int>()
            val sections = mutableListOf<WireSectionCursor.WireSection>()
            val stateMemo = mutableMapOf<Int, String>()
            for (sectionY in level.minSectionY..level.maxSectionY) {
                val section =
                    acquire.invoke(
                        engine,
                        0,
                        Math.floorDiv(x, 2),
                        Math.floorDiv(sectionY, 2),
                        Math.floorDiv(z, 2),
                    ) ?: continue
                val data =
                    try {
                        section.javaClass.getMethod("copyData").invoke(section) as LongArray
                    } finally {
                        section.javaClass.getMethod("release").invoke(section)
                    }
                val blocks = IntArray(4096)
                val bio = IntArray(64)
                val blockLight = ByteArray(2048)
                val skyLight = ByteArray(2048)
                var nonAir = 0
                var fluids = 0
                for (y in 0 until 16) for (localZ in 0 until 16) for (localX in 0 until 16) {
                    val index = (y shl 8) or (localZ shl 4) or localX
                    val voxelX = Math.floorMod(x, 2) * 16 + localX
                    val voxelY = Math.floorMod(sectionY, 2) * 16 + y
                    val voxelZ = Math.floorMod(z, 2) * 16 + localZ
                    val voxel = data[(voxelY shl 10) or (voxelZ shl 5) or voxelX]
                    val blockId = ((voxel ushr 27) and 0xfffff).toInt()
                    val light = (voxel ushr 56).toInt()
                    val state =
                        stateMemo.getOrPut(blockId) {
                            canonical(blockFor.invoke(mapper, blockId) as BlockState)
                        }
                    blocks[index] = dictionary.getOrPut(state) { dictionary.size }
                    if (
                        !state.startsWith("minecraft:air") &&
                            !state.startsWith("minecraft:cave_air") &&
                            !state.startsWith("minecraft:void_air")
                    )
                        nonAir++
                    if (state.startsWith("minecraft:water[") || state.startsWith("minecraft:lava["))
                        fluids++
                    blockLight[index shr 1] =
                        (blockLight[index shr 1].toInt() or
                                ((light and 15) shl ((index and 1) * 4)))
                            .toByte()
                    skyLight[index shr 1] =
                        (skyLight[index shr 1].toInt() or ((light ushr 4) shl ((index and 1) * 4)))
                            .toByte()
                    if (localX and 3 == 0 && y and 3 == 0 && localZ and 3 == 0) {
                        val biomeId = ((voxel ushr 47) and 511).toInt()
                        val biome =
                            if (biomeId < biomes.size) {
                                val entry = checkNotNull(biomes[biomeId])
                                entry.javaClass.getField("biome").get(entry) as String
                            } else "minecraft:plains"
                        bio[((y shr 2) shl 4) or ((localZ shr 2) shl 2) or (localX shr 2)] =
                            dictionary.getOrPut(biome) { dictionary.size }
                    }
                }
                sections +=
                    WireSectionCursor.WireSection(
                        sectionY,
                        nonAir,
                        fluids,
                        container(blocks, true),
                        container(bio, false),
                        blockLight,
                        skyLight,
                    )
            }
            if (sections.isEmpty()) return null
            return WireSectionCursor.emit(
                WireSectionCursor.WireColumn(dictionary.keys.toList(), sections),
                WireSectionCursor.Layout.V20,
            )
        } finally {
            engineClass.getMethod("releaseRef").invoke(engine)
        }
    }

    private fun canonical(state: BlockState): String {
        val properties =
            state.values.entries
                .associate { it.key.name to valueName(it.key, it.value) }
                .toSortedMap()
        return IdentityCodec.canonical(
            BuiltInRegistries.BLOCK.getKey(state.block).toString(),
            properties,
        )
    }

    private fun <T : Comparable<T>> valueName(property: Property<T>, value: Comparable<*>): String {
        @Suppress("UNCHECKED_CAST")
        return property.getName(value as T)
    }

    private fun container(values: IntArray, block: Boolean): WireSectionCursor.WireContainer {
        val palette = linkedMapOf<Int, Int>()
        val indices = values.map { palette.getOrPut(it) { palette.size } }.toIntArray()
        val bits =
            if (palette.size == 1) 0
            else maxOf(if (block) 4 else 1, 32 - Integer.numberOfLeadingZeros(palette.size - 1))
        return WireSectionCursor.WireContainer(
            bits,
            palette.keys.toIntArray(),
            if (bits == 0) longArrayOf() else WireSectionCursor.pack(indices, bits),
        )
    }
}
