package cc.taixue.lod.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeRegionStoreTest {
    @TempDir lateinit var root: Path

    @Test
    fun versionsWorldIsolationAndInterruptedAppendsSurviveReopen() {
        val key = ColumnCache.Key(UUID.randomUUID(), -1, -33)
        val store = NativeRegionStore(root)
        store.put(key, 100, byteArrayOf(1, 2))
        assertNull(store.get(key, 99))
        assertNull(store.get(key.copy(world = UUID.randomUUID()), 100))
        store.put(key, 101, byteArrayOf(3, 4))
        val file = root.resolve(key.world.toString()).resolve("-1_-2.native")
        Files.write(file, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
        val reopened = NativeRegionStore(root)
        assertArrayEquals(byteArrayOf(3, 4), reopened.get(key, 101))
        reopened.evict(reopened.entries().single())
        assertNull(reopened.get(key, 101))
    }
}
