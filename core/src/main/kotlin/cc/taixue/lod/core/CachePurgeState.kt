package cc.taixue.lod.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** A completed erasure stays acknowledged across restarts; unfinished erasures remain retryable. */
class CachePurgeState(private val file: Path) {
    private val values =
        Properties().apply {
            if (Files.exists(file)) Files.newInputStream(file).use(::load)
        }

    @Synchronized
    fun revision(world: UUID): Long =
        values.getProperty(world.toString())?.toLongOrNull()?.coerceAtLeast(0) ?: 0L

    @Synchronized
    fun completed(world: UUID, revision: Long) {
        require(revision > 0)
        if (revision <= revision(world)) return
        val next =
            Properties().apply {
                putAll(this@CachePurgeState.values)
                setProperty(world.toString(), revision.toString())
            }
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "purge-", ".tmp")
        try {
            Files.newOutputStream(temporary).use {
                next.store(it, "Completed server model erasures")
            }
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
            values.putAll(next)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
