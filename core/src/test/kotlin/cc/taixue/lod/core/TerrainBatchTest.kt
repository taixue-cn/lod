package cc.taixue.lod.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerrainBatchTest {
    @Test
    fun mixesModelsUnchangedAndMissingInOneFrame() {
        val items =
            listOf(
                TerrainBatch.Item(Wire.pack(-4, 12), 0, 1234, byteArrayOf(4, 5)),
                TerrainBatch.Item(2, 1),
                TerrainBatch.Item(3, 2),
            )
        val frames = TerrainBatch.encode("minecraft:summer-palace", items)
        assertEquals(1, frames.size)
        val decoded = TerrainBatch.decode(frames.single())
        assertEquals("minecraft:summer-palace", decoded.world)
        assertEquals(items.map { it.position }, decoded.items.map { it.position })
        assertEquals(listOf(0, 1, 2), decoded.items.map { it.type })
        assertEquals(1234L, decoded.items.first().timestamp)
        assertArrayEquals(byteArrayOf(4, 5), decoded.items.first().body)
    }

    @Test
    fun splitsByBothBytesAndCountWithoutLosingModels() {
        val items =
            (0L..19L).map { TerrainBatch.Item(it, 0, 10, ByteArray(130000) { it.toByte() }) }
        val frames = TerrainBatch.encode("world", items)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= TerrainBatch.MAX_BYTES })
        assertEquals(
            items.map { it.position },
            frames.flatMap { TerrainBatch.decode(it).items }.map { it.position },
        )
        assertEquals(
            3,
            TerrainBatch.encode("world", (0L..128L).map { TerrainBatch.Item(it, 1) }).size,
        )
    }

    @Test
    fun rejectsTruncationHostileLengthsUnknownTypesAndOversizedColumns() {
        val frame =
            TerrainBatch.encode("world", listOf(TerrainBatch.Item(0, 0, 1, byteArrayOf(1))))
                .single()
        assertThrows(IllegalArgumentException::class.java) {
            TerrainBatch.decode(frame.copyOf(frame.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerrainBatch.decode(Wire.Writer().varInt(1).utf("world").varInt(Int.MAX_VALUE).result())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerrainBatch.decode(
                Wire.Writer().varInt(1).utf("world").varInt(1).u8(3).i64(0).result()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerrainBatch.encode(
                "world",
                listOf(TerrainBatch.Item(0, 0, 1, ByteArray(Wire.MAX_FRAME))),
            )
        }
    }
}
