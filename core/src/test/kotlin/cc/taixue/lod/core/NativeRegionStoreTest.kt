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

    @Test
    fun pressureReleaseAndIndexEvictionKeepPersistedTerrainReadable() {
        val world = UUID.randomUUID()
        val store = NativeRegionStore(root, 1)
        val first = ColumnCache.Key(world, 0, 0)
        val second = ColumnCache.Key(world, 32, 0)
        store.put(first, 1, byteArrayOf(1))
        store.put(second, 2, byteArrayOf(2))
        val indexes =
            NativeRegionStore::class.java.getDeclaredField("indexes").apply { isAccessible = true }
        org.junit.jupiter.api.Assertions.assertEquals(1, (indexes.get(store) as Map<*, *>).size)
        store.setMemoryCaching(false)
        org.junit.jupiter.api.Assertions.assertEquals(0, (indexes.get(store) as Map<*, *>).size)
        assertArrayEquals(byteArrayOf(1), store.get(first, 1))
        assertArrayEquals(byteArrayOf(2), store.get(second, 2))
        org.junit.jupiter.api.Assertions.assertEquals(0, (indexes.get(store) as Map<*, *>).size)
        store.setMemoryCaching(true)
        assertArrayEquals(byteArrayOf(1), store.get(first, 1))
    }
}
