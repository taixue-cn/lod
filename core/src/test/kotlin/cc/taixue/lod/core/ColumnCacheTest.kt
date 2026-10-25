package cc.taixue.lod.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ColumnCacheTest {
    @TempDir lateinit var directory: Path

    @Test
    fun persistsButNeverCrossesWorlds() {
        val first = ColumnCache.Key(UUID.randomUUID(), -8, 11)
        val second = ColumnCache.Key(UUID.randomUUID(), -8, 11)
        val cache = ColumnCache(directory, Duration.ofMinutes(5), 10000)
        cache.put(first, ColumnCache.Entry(123, byteArrayOf(1, 2, 3)))
        assertNull(cache.get(second))
        cache.put(second, ColumnCache.Entry(456, byteArrayOf(4)))
        org.junit.jupiter.api.Assertions.assertEquals(
            setOf(first),
            cache.catalog(first.world).map { it.first }.toSet(),
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            setOf(first, second),
            cache.catalog().map { it.first }.toSet(),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            ColumnCache(directory, Duration.ofMinutes(5), 10000).get(first)!!.body,
        )
        cache.invalidate(first)
        assertNull(cache.get(first))
    }

    @Test
    fun corruptEntriesAreDiscardedAndSizeLimitEvicts() {
        val key = ColumnCache.Key(UUID.randomUUID(), 0, 0)
        val cache = ColumnCache(directory, Duration.ofMinutes(5), 0)
        cache.put(key, ColumnCache.Entry(1, byteArrayOf(7)))
        cache.trim()
        assertNull(cache.get(key))
        cache.put(key, ColumnCache.Entry(1, byteArrayOf(7)))
        Files.write(directory.resolve(key.world.toString()).resolve("0_0.gz"), byteArrayOf(1))
        assertNull(cache.get(key))
    }

    @Test
    fun headerProbeSurvivesRestartAndHonorsExpiryAndInvalidation() {
        val key = ColumnCache.Key(UUID.randomUUID(), 2, 3)
        val cache = ColumnCache(directory, Duration.ofSeconds(-1), -1)
        assertFalse(cache.contains(key))
        cache.put(key, ColumnCache.Entry(11, ByteArray(100000)))
        assertTrue(ColumnCache(directory, Duration.ofSeconds(-1), -1).contains(key))
        val file = directory.resolve(key.world.toString()).resolve("2_3.gz")
        Files.setLastModifiedTime(file, FileTime.fromMillis(1))
        assertTrue(cache.contains(key))
        assertFalse(ColumnCache(directory, Duration.ofSeconds(1), -1).contains(key))
        Files.write(file, ByteArray(40))
        assertFalse(cache.contains(key))
        cache.invalidate(key)
        assertFalse(cache.contains(key))
    }

    @Test
    fun unlimitedPersistentCacheSurvivesAgeAndTrim() {
        val key = ColumnCache.Key(UUID.randomUUID(), 2, -3)
        val cache = ColumnCache(directory, Duration.ofSeconds(-1), -1)
        cache.put(key, ColumnCache.Entry(11, byteArrayOf(4, 5)))
        Files.setLastModifiedTime(
            directory.resolve(key.world.toString()).resolve("2_-3.gz"),
            FileTime.fromMillis(1),
        )
        cache.trim()
        assertArrayEquals(
            byteArrayOf(4, 5),
            ColumnCache(directory, Duration.ofSeconds(-1), -1).get(key)!!.body,
        )
        cache.evict(cache.entries().first())
        assertNull(cache.get(key))
    }

    @Test
    fun aPressureAbortReleasesInventoryWithoutDeletingPersistedColumns() {
        val cache = ColumnCache(directory, Duration.ofDays(1), 10000)
        val key = ColumnCache.Key(UUID.randomUUID(), -32, 31)
        cache.put(key, ColumnCache.Entry(1, byteArrayOf(7)))
        org.junit.jupiter.api.Assertions.assertThrows(
            java.util.concurrent.CancellationException::class.java
        ) {
            cache.entries { throw java.util.concurrent.CancellationException("pressure") }
        }
        assertArrayEquals(byteArrayOf(7), cache.get(key)!!.body)
        org.junit.jupiter.api.Assertions.assertEquals(1, cache.entries().size)
    }
}
