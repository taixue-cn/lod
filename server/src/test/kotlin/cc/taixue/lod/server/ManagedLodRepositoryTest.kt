package cc.taixue.lod.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import no.jckf.dhsupport.core.database.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedLodRepositoryTest {
    @TempDir lateinit var root: Path

    private fun database(): Database =
        Database().apply {
            open(root.resolve("lods.db").toString())
            connection.createStatement().use {
                it.execute(
                    "CREATE TABLE lods(worldId TEXT,x INTEGER,z INTEGER,data BLOB,beacons BLOB,timestamp INTEGER,version INTEGER,PRIMARY KEY(worldId,x,z))"
                )
            }
        }

    @Test
    fun purgeDeletesRowsAndReleasesDiskSpaceWithoutTouchingOtherWorlds() {
        val database = database()
        val repository = ManagedLodRepository(database) { true }
        repository.setLogger(Logger.getAnonymousLogger())
        try {
            val world = UUID.randomUUID()
            val other = UUID.randomUUID()
            repeat(8) {
                repository
                    .saveLodAsync(world, it, 0, ByteArray(256 * 1024) { 42 }, byteArrayOf(), 3)
                    .join()
            }
            repository.saveLodAsync(other, 0, 0, byteArrayOf(9), byteArrayOf(), 3).join()
            database.connection.createStatement().use {
                it.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
            val before = Files.size(root.resolve("lods.db"))
            repository.blockWorld(world)
            assertEquals(8, repository.purgeWorldAsync(world).get(10, TimeUnit.SECONDS))
            assertFalse(repository.lodExistsAsync(world, 0, 0).join())
            assertTrue(repository.lodExistsAsync(other, 0, 0).join())
            assertTrue(
                Files.size(root.resolve("lods.db")) < before,
                "A DELETE alone would leave database pages allocated",
            )
            assertNull(
                repository.saveLodAsync(world, 0, 0, byteArrayOf(1), byteArrayOf(), 3).join()
            )
        } finally {
            repository.close()
            database.close()
        }
    }

    @Test
    fun queuedWritesCannotRepopulateADisabledWorld() {
        val database = database()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository =
            ManagedLodRepository(database) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                true
            }
        repository.setLogger(Logger.getAnonymousLogger())
        try {
            val world = UUID.randomUUID()
            val running = repository.saveLodAsync(world, 0, 0, byteArrayOf(1), byteArrayOf(), 3)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = repository.saveLodAsync(world, 1, 0, byteArrayOf(2), byteArrayOf(), 3)
            repository.blockWorld(world)
            val purge = repository.purgeWorldAsync(world)
            release.countDown()
            running.get(5, TimeUnit.SECONDS)
            assertNull(queued.get(5, TimeUnit.SECONDS))
            purge.get(5, TimeUnit.SECONDS)
            assertTrue(repository.cacheEntriesAsync().join().isEmpty())
            repository.allowWorld(world)
            assertNotNull(
                repository.saveLodAsync(world, 2, 0, byteArrayOf(3), byteArrayOf(), 3).join()
            )
        } finally {
            release.countDown()
            repository.close()
            database.close()
        }
    }
}
