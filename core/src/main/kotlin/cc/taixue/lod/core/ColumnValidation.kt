package cc.taixue.lod.core

import dev.vox.lss.common.wire.IdentityCodec
import dev.vox.lss.common.wire.WireSectionCursor

/** Semantic validation of client palettes, in addition to the upstream wire framing checks. */
object ColumnValidation {
    @JvmStatic
    fun validate(
        body: ByteArray,
        minimumSection: Int,
        maximumSection: Int,
    ): WireSectionCursor.WireColumn {
        require(body.size <= Wire.MAX_FRAME) { "column too large" }
        val column = WireSectionCursor.parse(body, WireSectionCursor.Layout.V20)
        require(
            column.dictionary().size <= 65536 &&
                column.sections().size <= maximumSection - minimumSection
        ) {
            "column bounds"
        }
        column.dictionary().forEach(IdentityCodec::validate)
        val seen = mutableSetOf<Int>()
        for (section in column.sections()) {
            require(
                section.sectionY() in minimumSection until maximumSection &&
                    seen.add(section.sectionY())
            ) {
                "section range/duplicate"
            }
            require(section.nonEmptyBlockCount() in 0..4096 && section.fluidCount() in 0..4096) {
                "section count"
            }
            values(section.blocks(), 4096)
            values(section.biomes(), 64)
        }
        return column
    }

    @JvmStatic
    fun values(container: WireSectionCursor.WireContainer, size: Int): IntArray {
        if (container.bits() == 0) return IntArray(size) { container.palette()[0] }
        val indices = WireSectionCursor.unpack(container.data(), container.bits(), size)
        for (index in indices.indices) {
            require(indices[index] in container.palette().indices) { "palette index" }
            indices[index] = container.palette()[indices[index]]
        }
        return indices
    }
}
