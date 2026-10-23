package cc.taixue.lod.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class LegacyDataDirectoryTest {
    @TempDir lateinit var root: Path

    @Test
    fun firstInstallDoesNotCreateDefaultsDuringMigration() {
        val current = root.resolve("LodServer")
        LegacyDataDirectory.migrate(root.resolve("ArchLOD"), current)
        assertFalse(Files.exists(current))
    }

    @Test
    fun movesConfigAndNestedCacheWithoutChangingBytesOrTimestamps() {
        val legacy = root.resolve("ArchLOD")
        val current = root.resolve("LodServer")
        Files.createDirectories(legacy.resolve("voxy-cache/world"))
        Files.writeString(legacy.resolve("config.yml"), "upload: false\n")
        val bytes = byteArrayOf(0, 1, -1, 33)
        Files.write(legacy.resolve("voxy-cache/world/0_0.gz"), bytes)
        Files.setLastModifiedTime(
            legacy.resolve("voxy-cache/world/0_0.gz"),
            FileTime.fromMillis(123000),
        )
        LegacyDataDirectory.migrate(legacy, current)
        assertFalse(Files.exists(legacy))
        assertEquals("upload: false\n", Files.readString(current.resolve("config.yml")))
        assertArrayEquals(bytes, Files.readAllBytes(current.resolve("voxy-cache/world/0_0.gz")))
        assertEquals(
            123000L,
            Files.getLastModifiedTime(current.resolve("voxy-cache/world/0_0.gz")).toMillis(),
        )
    }

    @Test
    fun supportsAnEmptyDirectoryCreatedByThePluginLoader() {
        val legacy = Files.createDirectory(root.resolve("ArchLOD"))
        val current = Files.createDirectory(root.resolve("LodServer"))
        Files.writeString(legacy.resolve("config.yml"), "existing")
        LegacyDataDirectory.migrate(legacy, current)
        assertEquals("existing", Files.readString(current.resolve("config.yml")))
    }

    @Test
    fun refusesToOverwriteEitherNonemptyDirectory() {
        val legacy = Files.createDirectory(root.resolve("ArchLOD"))
        val current = Files.createDirectory(root.resolve("LodServer"))
        Files.writeString(legacy.resolve("config.yml"), "old")
        Files.writeString(current.resolve("config.yml"), "new")
        assertThrows<IllegalStateException> { LegacyDataDirectory.migrate(legacy, current) }
        assertEquals("old", Files.readString(legacy.resolve("config.yml")))
        assertEquals("new", Files.readString(current.resolve("config.yml")))
    }
}
