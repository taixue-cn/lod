package cc.taixue.lod.core

import dev.vox.lss.common.wire.WireSectionCursor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Registry-independent Voxy cells and all five mip levels, prepared once on the server. */
object PreparedColumn {
    const val CELLS = 4681
    const val MAX_INFLATED = 8 * 1024 * 1024

    data class Section(val y: Int, val nonAir: Int, val palette: LongArray, val runs: IntArray)

    data class Column(
        val dictionary: List<String>,
        val opacity: IntArray,
        val sections: List<Section>,
    )

    // Portable cells use dictionary IDs, never another client's private Voxy mapping IDs.
    fun block(cell: Long) = (cell and 0xfffff).toInt()

    fun biome(cell: Long) = ((cell ushr 20) and 0xfffff).toInt()

    fun light(cell: Long) = (cell ushr 40).toInt()

    fun cell(block: Int, biome: Int, light: Int) =
        block.toLong() or (biome.toLong() shl 20) or (light.toLong() shl 40)

    fun prepare(body: ByteArray, opacityFor: (String) -> Int): ByteArray {
        val source = WireSectionCursor.parse(body, WireSectionCursor.Layout.V20)
        val dictionary = source.dictionary()
        val opacity = IntArray(dictionary.size) { opacityFor(dictionary[it]).coerceIn(0, 15) }
        val airIds =
            BooleanArray(dictionary.size) {
                dictionary[it].substringBefore('[') in
                    setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
            }
        val output = Wire.Writer().varInt(1).varInt(dictionary.size)
        dictionary.forEachIndexed { i, identity -> output.utf(identity).u8(opacity[i]) }
        output.varInt(source.sections().size)
        for (section in source.sections()) {
            val singleBlock =
                // Server-owned counts also cover old palettes retaining unused non-air entries.
                if (
                    section.nonEmptyBlockCount() == 0 ||
                        section.blocks().palette().all { airIds[it] }
                )
                    0
                else if (section.blocks().bits() == 0) section.blocks().palette()[0] + 1 else null
            val singleBiome =
                if (singleBlock == 0) 0
                else if (section.biomes().bits() == 0) section.biomes().palette()[0] else null
            val sky = uniformLight(section.skyLight())
            val emitted = uniformLight(section.blockLight())
            if (singleBlock != null && singleBiome != null && sky != null && emitted != null) {
                output
                    .u8(section.sectionY())
                    .varInt(section.nonEmptyBlockCount())
                    .varInt(1)
                    .varInt(singleBlock)
                    .varInt(singleBiome)
                    .u8(sky or (emitted shl 4))
                    .varInt(1)
                    .varInt(CELLS)
                    .varInt(0)
                continue
            }
            val blocks = ColumnValidation.values(section.blocks(), 4096)
            val biomes = ColumnValidation.values(section.biomes(), 64)
            val cells = LongArray(CELLS)
            for (i in 0 until 4096) {
                val air = airIds[blocks[i]]
                val b = if (air) 0 else blocks[i] + 1
                val bio =
                    if (air) 0
                    else
                        biomes[
                            ((i shr 8) shr 2 shl 4) or
                                (((i shr 4) and 15) shr 2 shl 2) or
                                ((i and 15) shr 2)]
                cells[i] =
                    cell(
                        b,
                        bio,
                        nibble(section.skyLight(), i) or (nibble(section.blockLight(), i) shl 4),
                    )
            }
            var from = 0
            var to = 4096
            var width = 16
            repeat(4) {
                val next = width / 2
                for (y in 0 until next) for (z in 0 until next) for (x in 0 until next) {
                    var best = -1
                    var chosen = 0L
                    var sky = 0
                    var blockLight = 0
                    for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
                        val c =
                            cells[from + ((y * 2 + dy) * width + z * 2 + dz) * width + x * 2 + dx]
                        val l = light(c)
                        sky += l and 15
                        blockLight += l and 240
                        if (block(c) != 0) {
                            val score =
                                (opacity[block(c) - 1] shl 4) or (dx shl 2) or (dy shl 1) or dz
                            if (score > best) {
                                best = score
                                chosen = c
                            }
                        }
                    }
                    if (best == -1)
                        chosen = cell(0, 0, ((blockLight / 8) and 240) or ((sky + 7) / 8))
                    cells[to + (y * next + z) * next + x] = chosen
                }
                from = to
                to += next * next * next
                width = next
            }
            val palette = linkedMapOf<Long, Int>()
            val runs = mutableListOf<Int>()
            var start = 0
            while (start < cells.size) {
                val value = cells[start]
                var end = start + 1
                while (end < cells.size && cells[end] == value) end++
                runs += end - start
                runs += palette.getOrPut(value) { palette.size }
                start = end
            }
            output.u8(section.sectionY()).varInt(section.nonEmptyBlockCount()).varInt(palette.size)
            palette.keys.forEach { output.varInt(block(it)).varInt(biome(it)).u8(light(it)) }
            output.varInt(runs.size / 2)
            runs.forEach(output::varInt)
        }
        val raw = output.result()
        require(raw.size <= MAX_INFLATED)
        return ByteArrayOutputStream()
            .also { bytes -> GZIPOutputStream(bytes).use { it.write(raw) } }
            .toByteArray()
    }

    fun decode(compressed: ByteArray): Column {
        require(compressed.size <= Wire.MAX_FRAME)
        val raw =
            GZIPInputStream(ByteArrayInputStream(compressed)).use {
                it.readNBytes(MAX_INFLATED + 1)
            }
        require(raw.size <= MAX_INFLATED) { "native cache inflate limit" }
        // Prepared columns may inflate beyond a network frame, but are independently bounded above.
        val input = java.io.DataInputStream(ByteArrayInputStream(raw))
        fun byte() = input.readUnsignedByte()
        fun number(max: Int): Int {
            var n = 0
            for (shift in 0 until 35 step 7) {
                val b = byte()
                require(shift != 28 || b and 240 == 0)
                n = n or ((b and 127) shl shift)
                if (b and 128 == 0) {
                    require(n in 0..max)
                    return n
                }
            }
            error("native varint")
        }
        require(number(1) == 1)
        val count = number(65536)
        val dictionary = mutableListOf<String>()
        val opacity = IntArray(count)
        repeat(count) {
            val length = number(4096)
            require(length <= input.available())
            val identity = input.readNBytes(length).toString(Charsets.UTF_8)
            dev.vox.lss.common.wire.IdentityCodec.validate(identity)
            dictionary += identity
            opacity[it] = byte().also { require(it <= 15) }
        }
        val sections = mutableListOf<Section>()
        val seen = mutableSetOf<Int>()
        repeat(number(256)) {
            val y = input.readByte().toInt()
            require(seen.add(y))
            val nonAir = number(4096)
            val paletteSize = number(CELLS)
            require(paletteSize > 0)
            val palette =
                LongArray(paletteSize) {
                    val b = number(count)
                    val bio = number(maxOf(0, count - 1))
                    val l = byte()
                    cell(b, bio, l)
                }
            val pairs = number(CELLS)
            require(pairs > 0)
            var cells = 0
            var actualNonAir = 0
            val runs = IntArray(pairs * 2)
            repeat(pairs) {
                val length = number(CELLS)
                require(length > 0)
                val previous = cells
                cells += length
                require(cells <= CELLS)
                runs[it * 2] = length
                runs[it * 2 + 1] = number(paletteSize - 1)
                if (previous < 4096 && block(palette[runs[it * 2 + 1]]) != 0)
                    actualNonAir += minOf(cells, 4096) - previous
            }
            require(cells == CELLS && actualNonAir == nonAir)
            sections += Section(y, nonAir, palette, runs)
        }
        require(input.available() == 0)
        return Column(dictionary, opacity, sections)
    }

    private fun uniformLight(data: ByteArray?): Int? {
        if (data == null) return 0
        val first = data[0].toInt() and 255
        if ((first and 15) != (first ushr 4) || data.any { (it.toInt() and 255) != first })
            return null
        return first and 15
    }

    private fun nibble(data: ByteArray?, i: Int): Int =
        if (data == null) 0 else (data[i shr 1].toInt() ushr ((i and 1) * 4)) and 15
}
