package cc.taixue.lod.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedChunksTest {
    @TempDir lateinit var directory: Path

    @Test
    fun scansOnlySavedSlotsAndHandlesNegativeRegions() {
        val data = ByteArray(16384)
        val buffer = ByteBuffer.wrap(data)
        buffer.putInt(0, 513)
        buffer.putInt(4, 769)
        buffer.putInt(1023 * 4, 513)
        put(buffer, 8192, "minecraft:full")
        put(buffer, 12288, "minecraft:noise")
        Files.write(directory.resolve("r.-1.2.mca"), data)
        val scan = SavedChunks(directory)
        assertEquals(SavedChunks.Chunk(-32, 64, -1, 2), scan.next())
        assertTrue(scan.lastVerifiedFull)
        assertEquals(SavedChunks.Chunk(-1, 95, -1, 2), scan.next())
        assertNull(scan.next())
        val selected = SavedChunks(directory)
        val visited = mutableListOf<SavedChunks.Chunk>()
        assertEquals(
            SavedChunks.Chunk(-1, 95, -1, 2),
            selected.next { chunk ->
                visited += chunk
                chunk.x != -32
            },
        )
        assertEquals(3, visited.size)
        assertNull(selected.next { false })
        Files.list(directory).use { assertEquals(1L, it.count()) }
    }

    @Test
    fun oversizedExternalChunksAreSkippedWithoutReadingThem() {
        val data = ByteArray(12288)
        val buffer = ByteBuffer.wrap(data)
        buffer.putInt(0, 513)
        buffer.position(8192)
        buffer.putInt(1)
        buffer.put(130.toByte())
        Files.write(directory.resolve("r.0.0.mca"), data)
        RandomAccessFile(directory.resolve("c.0.0.mcc").toFile(), "rw").use {
            it.setLength(33554433)
        }
        assertNull(SavedChunks(directory).next())
    }

    @Test
    fun lz4CandidatesStillRequirePapersGeneratedStatusCheck() {
        val data = ByteArray(12288)
        val buffer = ByteBuffer.wrap(data)
        buffer.putInt(0, 513)
        buffer.position(8192)
        buffer.putInt(1)
        buffer.put(4.toByte())
        Files.write(directory.resolve("r.0.0.mca"), data)
        val scan = SavedChunks(directory)
        assertEquals(SavedChunks.Chunk(0, 0, 0, 0), scan.next())
        assertFalse(scan.lastVerifiedFull)
    }

    private fun put(buffer: ByteBuffer, offset: Int, status: String) {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { output ->
            output.writeByte(10)
            output.writeUTF("")
            output.writeByte(8)
            output.writeUTF("Status")
            output.writeUTF(status)
            output.writeByte(0)
        }
        val zipped = ByteArrayOutputStream()
        DeflaterOutputStream(zipped).use { it.write(raw.toByteArray()) }
        val bytes = zipped.toByteArray()
        buffer.position(offset)
        buffer.putInt(bytes.size + 1)
        buffer.put(2.toByte())
        buffer.put(bytes)
    }
}
