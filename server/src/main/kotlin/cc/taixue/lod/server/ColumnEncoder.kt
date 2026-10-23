package cc.taixue.lod.server

import dev.vox.lss.common.wire.IdentityCodec
import dev.vox.lss.common.wire.WireSectionCursor
import org.bukkit.ChunkSnapshot
import org.bukkit.Material

/** Encodes immutable Paper snapshots into LSS's version-neutral v20 body. */
object ColumnEncoder {
    @JvmStatic
    fun encode(snapshot: ChunkSnapshot, minY: Int, maxY: Int): ByteArray {
        val dictionary = linkedMapOf<String, Int>()
        val sections = mutableListOf<WireSectionCursor.WireSection>()
        val identities = mutableMapOf<String, String>()
        for (sectionY in Math.floorDiv(minY, 16) until Math.floorDiv(maxY, 16)) {
            val blocks = IntArray(4096)
            val biomes = IntArray(64)
            val blockLight = ByteArray(2048)
            val skyLight = ByteArray(2048)
            var count = 0
            var fluids = 0
            var hasBlockLight = false
            var hasSkyLight = false
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) {
                val index = (y shl 8) or (z shl 4) or x
                val worldY = sectionY * 16 + y
                val state = snapshot.getBlockData(x, worldY, z)
                val identity = identities.getOrPut(state.asString) { canonical(state.asString) }
                blocks[index] = dictionary.getOrPut(identity) { dictionary.size }
                if (!state.material.isAir) count++
                if (state.material == Material.WATER || state.material == Material.LAVA) fluids++
                val block = snapshot.getBlockEmittedLight(x, worldY, z)
                val sky = snapshot.getBlockSkyLight(x, worldY, z)
                blockLight[index shr 1] =
                    (blockLight[index shr 1].toInt() or (block shl ((index and 1) * 4))).toByte()
                skyLight[index shr 1] =
                    (skyLight[index shr 1].toInt() or (sky shl ((index and 1) * 4))).toByte()
                hasBlockLight = hasBlockLight || block != 0
                hasSkyLight = hasSkyLight || sky != 0
            }
            for (y in 0 until 4) for (z in 0 until 4) for (x in 0 until 4) {
                val biome = snapshot.getBiome(x * 4, sectionY * 16 + y * 4, z * 4).key.toString()
                biomes[(y shl 4) or (z shl 2) or x] = dictionary.getOrPut(biome) { dictionary.size }
            }
            sections +=
                WireSectionCursor.WireSection(
                    sectionY,
                    count,
                    fluids,
                    container(blocks, true),
                    container(biomes, false),
                    blockLight.takeIf { hasBlockLight },
                    skyLight.takeIf { hasSkyLight },
                )
        }
        return WireSectionCursor.emit(
            WireSectionCursor.WireColumn(dictionary.keys.toList(), sections),
            WireSectionCursor.Layout.V20,
        )
    }

    private fun canonical(state: String): String {
        val bracket = state.indexOf('[')
        if (bracket < 0) return state
        val properties =
            state
                .substring(bracket + 1, state.length - 1)
                .split(',')
                .associate {
                    val pair = it.split('=', limit = 2)
                    pair[0] to pair[1]
                }
                .toSortedMap()
        return IdentityCodec.canonical(state.substring(0, bracket), properties)
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
