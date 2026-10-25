package cc.taixue.lod.server

import cc.taixue.lod.core.CacheQuota
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.logging.Logger
import no.jckf.dhsupport.core.database.Database
import no.jckf.dhsupport.core.database.repositories.AsyncLodRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DhCacheQuotaTest {
    @TempDir lateinit var directory: Path

    @Test
    fun dhSharesTheBudgetAndKeepsExistingModelsReadableWhenFull() {
        val database = Database()
        database.open(directory.resolve("data.sqlite").toString())
        val repository = AsyncLodRepository(database)
        repository.logger = Logger.getAnonymousLogger()
        try {
            database.connection.createStatement().use {
                it.execute(
                    "CREATE TABLE lods(worldId TEXT,x INTEGER,z INTEGER,data BLOB,beacons BLOB,timestamp INTEGER,version INTEGER,PRIMARY KEY(worldId,x,z))"
                )
            }
            val world = UUID.randomUUID()
            val quota = CacheQuota()
            quota.configure(8, emptyMap())
            quota.initialize(emptyMap())
            repository.cacheQuota = quota
            repository.saveLodAsync(world, 0, 0, byteArrayOf(1, 2, 3), byteArrayOf(4, 5), 16).join()
            quota.reserve(world, 3).use { it.commit() }
            assertNotNull(quota.pauseReason())
            repeat(3) {
                assertThrows(CompletionException::class.java) {
                    repository.saveLodAsync(world, 1, 0, byteArrayOf(6), null, 16).join()
                }
            }
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                repository.loadLodAsync(world, 0, 0).join().data,
            )
            assertNull(repository.loadLodAsync(world, 1, 0).join())
            quota.configure(16, emptyMap())
            repository.saveLodAsync(world, 1, 0, ByteArray(4), null, 16).join()
            repository.saveLodAsync(world, 0, 0, ByteArray(2), null, 16).join()
            assertTrue(quota.status().contains("cache bytes=9"))
            assertTrue(repository.deleteLodAsync(world, 1, 0).join())
            assertTrue(quota.status().contains("cache bytes=5"))
            assertEquals(mapOf(world to 2L), repository.cacheUsageAsync().join())
        } finally {
            repository.close()
            database.close()
        }
    }
}
