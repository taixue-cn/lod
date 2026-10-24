package cc.taixue.lod.server

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import no.jckf.dhsupport.core.database.Database
import no.jckf.dhsupport.core.database.models.LodModel
import no.jckf.dhsupport.core.database.repositories.AsyncLodRepository

/** Serializes DH cache erasure with its existing SQLite queue and fences delayed generation. */
class ManagedLodRepository
@JvmOverloads
constructor(
    database: Database,
    private val allowed: (UUID) -> Boolean = DhGuard::cacheAllowed,
) : AsyncLodRepository(database) {
    private val blocked = ConcurrentHashMap.newKeySet<UUID>()
    private val generations = ConcurrentHashMap<UUID, Long>()

    /** Revoke writes before submitting the deletion task. */
    fun blockWorld(world: UUID) {
        if (blocked.add(world)) generations.merge(world, 1L, Long::plus)
    }

    /** The caller has already waited for deletion and compaction to finish. */
    fun allowWorld(world: UUID) {
        blocked.remove(world)
    }

    override fun saveLod(
        worldId: UUID,
        x: Int,
        z: Int,
        data: ByteArray,
        beacons: ByteArray?,
        version: Int,
    ): LodModel? {
        if (worldId in blocked || !allowed(worldId)) return null
        return super.saveLod(worldId, x, z, data, beacons, version)
    }

    override fun saveLodAsync(
        worldId: UUID,
        x: Int,
        z: Int,
        data: ByteArray,
        beacons: ByteArray?,
        version: Int,
    ): CompletableFuture<LodModel?> {
        val generation = generations[worldId]
        return queueTask {
            if (generation != generations[worldId]) null
            else saveLod(worldId, x, z, data, beacons, version)
        }
    }

    /** DELETE removes records; vacuum and WAL truncation return their storage to the filesystem. */
    fun purgeWorldAsync(world: UUID): CompletableFuture<Int> = queueTask {
        check(world in blocked) { "Block cache writes before purging" }
        database.clearQueryCache()
        val removed =
            database.connection.prepareStatement("DELETE FROM lods WHERE worldId=?").use {
                it.setString(1, world.toString())
                it.executeUpdate()
            }
        val freePages =
            database.connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA freelist_count").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        // Retry reclamation after a previous DELETE succeeded but vacuum failed.
        if (removed > 0 || freePages > 0) {
            database.clearQueryCache()
            database.connection.createStatement().use { statement ->
                val mode =
                    statement.executeQuery("PRAGMA auto_vacuum").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                if (mode == 0) {
                    statement.execute("PRAGMA auto_vacuum=INCREMENTAL")
                    statement.execute("VACUUM")
                } else {
                    statement.execute("PRAGMA incremental_vacuum")
                }
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        removed
    }
}
