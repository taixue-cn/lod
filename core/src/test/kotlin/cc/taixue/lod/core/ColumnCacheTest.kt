package cc.taixue.lod.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun purgeRemovesOnlyItsWorldAndRejectsWritesFromBeforeDisable() {
        val world = UUID.randomUUID()
        val other = UUID.randomUUID()
        val cache = ColumnCache(directory, Duration.ofSeconds(-1), -1)
        val key = ColumnCache.Key(world, 0, 0)
        val foreign = ColumnCache.Key(other, 0, 0)
        val entry = ColumnCache.Entry(1, byteArrayOf(7, 8))
        val old = cache.generation(world)
        cache.put(key, entry, old)
        cache.put(foreign, entry)
        cache.blockWorld(world)
        cache.purgeWorld(world)
        org.junit.jupiter.api.Assertions.assertFalse(
            Files.exists(directory.resolve(world.toString()))
        )
        assertArrayEquals(entry.body, cache.get(foreign)!!.body)
        cache.allowWorld(world)
        cache.put(key, entry, old)
        assertNull(
            cache.get(key),
            "An old queued writer cannot resurrect a deleted world after re-enable",
        )
        cache.put(key, entry, cache.generation(world))
        assertArrayEquals(entry.body, cache.get(key)!!.body)
    }
}
