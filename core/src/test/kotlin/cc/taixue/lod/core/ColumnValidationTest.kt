package cc.taixue.lod.core

import dev.vox.lss.common.wire.WireSectionCursor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ColumnValidationTest {
    private fun column(sectionY: Int, paletteValue: Int, index: Int): ByteArray {
        val values = IntArray(4096).also { it[0] = index }
        val block =
            WireSectionCursor.WireContainer(
                4,
                intArrayOf(paletteValue),
                WireSectionCursor.pack(values, 4),
            )
        val biome = WireSectionCursor.WireContainer(0, intArrayOf(1), longArrayOf())
        val section = WireSectionCursor.WireSection(sectionY, 4096, 0, block, biome, null, null)
        return WireSectionCursor.emit(
            WireSectionCursor.WireColumn(
                listOf("minecraft:stone", "minecraft:plains"),
                listOf(section),
            ),
            WireSectionCursor.Layout.V20,
        )
    }

    @Test
    fun validatesWorldHeightAndActualPackedIndices() {
        assertEquals(1, ColumnValidation.validate(column(0, 0, 0), -4, 20).sections().size)
        assertThrows<IllegalArgumentException> {
            ColumnValidation.validate(column(20, 0, 0), -4, 20)
        }
        assertThrows<IllegalArgumentException> {
            ColumnValidation.validate(column(0, 0, 1), -4, 20)
        }
    }
}
