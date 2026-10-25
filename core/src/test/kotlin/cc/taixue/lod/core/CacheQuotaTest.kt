package cc.taixue.lod.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CacheQuotaTest {
    @TempDir lateinit var directory: Path
    private val world = UUID.randomUUID()

    @Test
    fun reservationsShareSpaceAndFailedWritesReturnIt() {
        val quota = CacheQuota()
        quota.configure(100, emptyMap())
        assertThrows(CacheQuota.Full::class.java) { quota.reserve(world, 1) }
        quota.initialize(emptyMap())
        quota.reserve(world, 60).use { it.commit() }
        quota.reserve(UUID.randomUUID(), 30).use {
            assertThrows(CacheQuota.Full::class.java) { quota.reserve(world, 11) }
        }
        assertNull(quota.pauseReason())
        quota.reserve(world, 40).use { it.commit() }
        assertEquals("global storage quota reached", quota.pauseReason())
        quota.configure(200, emptyMap())
        assertNull(quota.pauseReason())
        assertTrue(quota.status().contains("cache bytes=100"))
    }

    @Test
    fun deletionAndSmallerReplacementsResumeWorkWithoutQuotaEviction() {
        val quota = CacheQuota()
        quota.configure(100, mapOf(world to 80))
        quota.initialize(mapOf(world to 70))
        assertThrows(CacheQuota.Full::class.java) { quota.reserve(world, 20) }
        assertTrue(quota.pauseReason()!!.startsWith("world storage quota"))
        quota.removed(world, 20)
        assertNull(quota.pauseReason())
        quota.configure(40, emptyMap())
        quota.reserve(world, -20).use { it.commit() }
        assertNull(quota.pauseReason())
        assertTrue(quota.status().contains("cache bytes=30"))
    }

    @Test
    fun fullQuotaKeepsCanonicalAndNativeCacheReadableAndNeverRollsOldData() {
        val quota = CacheQuota()
        quota.configure(-1, emptyMap())
        quota.initialize(emptyMap())
        val key = ColumnCache.Key(world, 0, 0)
        val cache = ColumnCache(directory.resolve("voxy"), Duration.ofSeconds(-1), -1)
        cache.quota = quota
        val native = NativeRegionStore(directory.resolve("native"))
        native.quota = quota
        cache.put(key, ColumnCache.Entry(1, byteArrayOf(7, 8)))
        native.put(key, 1, byteArrayOf(9, 10))
        val bytes = cache.entries().sumOf { it.bytes } + native.entries().sumOf { it.bytes }
        quota.configure(bytes, emptyMap())
        repeat(3) {
            assertThrows(CacheQuota.Full::class.java) {
                cache.put(ColumnCache.Key(world, 1, 0), ColumnCache.Entry(2, byteArrayOf(1)))
            }
            assertThrows(CacheQuota.Full::class.java) {
                native.put(ColumnCache.Key(world, 1, 0), 2, byteArrayOf(3))
            }
        }
        assertArrayEquals(byteArrayOf(7, 8), cache.get(key)!!.body)
        assertArrayEquals(byteArrayOf(9, 10), native.get(key, 1))
        assertEquals(
            bytes,
            cache.entries().sumOf { it.bytes } + native.entries().sumOf { it.bytes },
        )
        Files.walk(directory).use { paths ->
            assertFalse(paths.anyMatch { it.toString().endsWith(".tmp") })
        }
        cache.invalidate(key)
        assertNull(quota.pauseReason())
    }
}
