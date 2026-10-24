package cc.taixue.lod.core

import dev.vox.lss.common.wire.WireSectionCursor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PreparedColumnTest {
    private fun body(blocks: IntArray): ByteArray {
        val container =
            WireSectionCursor.WireContainer(4, intArrayOf(0, 1), WireSectionCursor.pack(blocks, 4))
        return WireSectionCursor.emit(
            WireSectionCursor.WireColumn(
                listOf("minecraft:air", "minecraft:gold_block", "minecraft:plains"),
                listOf(
                    WireSectionCursor.WireSection(
                        0,
                        blocks.count { it != 0 },
                        0,
                        container,
                        WireSectionCursor.WireContainer(0, intArrayOf(2), longArrayOf()),
                        null,
                        ByteArray(2048) { 0xff.toByte() },
                    )
                ),
            ),
            WireSectionCursor.Layout.V20,
        )
    }

    @Test
    fun preservesCellsLightsAndAllMipLevels() {
        val blocks = IntArray(4096)
        blocks[4095] = 1
        val prepared = PreparedColumn.decode(PreparedColumn.prepare(body(blocks)) { 15 })
        val section = prepared.sections.single()
        val cells =
            section.runs.toList().chunked(2).flatMap { (length, index) ->
                List(length) { section.palette[index] }
            }
        assertEquals(4681, cells.size)
        assertEquals(2, PreparedColumn.block(cells[4095]))
        assertEquals(2, PreparedColumn.biome(cells[4095]))
        assertEquals(15, PreparedColumn.light(cells[4095]))
        assertEquals(2, PreparedColumn.block(cells.last()))
        assertEquals(0, PreparedColumn.block(cells.first()))
        assertEquals(1, section.nonAir)
    }

    @Test
    fun airCollapsesToOneRunAndMalformedDataIsRejected() {
        val bytes = PreparedColumn.prepare(body(IntArray(4096))) { 0 }
        assertArrayEquals(intArrayOf(4681, 0), PreparedColumn.decode(bytes).sections.single().runs)
        assertThrows(Exception::class.java) { PreparedColumn.decode(bytes.copyOf(bytes.size / 2)) }
    }
}
