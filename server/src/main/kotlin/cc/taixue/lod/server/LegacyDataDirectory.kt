package cc.taixue.lod.server

import java.nio.file.Files
import java.nio.file.Path

/** Moves existing configuration and cache together before the renamed plugin creates defaults. */
internal object LegacyDataDirectory {
    fun migrate(legacy: Path, current: Path) {
        if (!Files.exists(legacy)) return
        if (Files.exists(current)) {
            check(
                Files.isDirectory(current) && Files.list(current).use { !it.findAny().isPresent }
            ) {
                "Both $legacy and $current exist. Merge the data directories before starting LodServer; no data was overwritten."
            }
            Files.delete(current)
        }
        // Both paths share a parent. A filesystem rename preserves the cached files and their
        // timestamps.
        Files.move(legacy, current)
    }
}
