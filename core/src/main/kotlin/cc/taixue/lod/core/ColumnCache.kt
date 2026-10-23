package cc.taixue.lod.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** World-scoped atomic cache; corrupt and expired entries are rebuilt from saved terrain. */
class ColumnCache
@Throws(IOException::class)
constructor(
    private val root: Path,
    maxAge: Duration,
    private val maxBytes: Long,
) {
    @JvmRecord data class Key(val world: UUID, val x: Int, val z: Int)

    @JvmRecord data class Entry(val timestamp: Long, val body: ByteArray)

    private val maxAgeMillis = maxAge.toMillis()

    init {
        Files.createDirectories(root)
    }

    private fun path(key: Key): Path =
        root.resolve(key.world.toString()).resolve("${key.x}_${key.z}.gz")

    @Synchronized
    fun get(key: Key): Entry? {
        val path = path(key)
        return try {
            if (
                !Files.isRegularFile(path) ||
                    (maxAgeMillis >= 0 &&
                        System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() >
                            maxAgeMillis)
            )
                return null
            DataInputStream(GZIPInputStream(Files.newInputStream(path))).use { input ->
                if (input.readInt() != 0x41524c31) throw IOException("cache version")
                val timestamp = input.readLong()
                val size = input.readInt()
                if (size !in 0..Wire.MAX_FRAME) throw IOException("cache length")
                val body = input.readNBytes(size)
                if (body.size != size || input.read() != -1) throw IOException("cache truncated")
                Entry(timestamp, body)
            }
        } catch (_: IOException) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // A failed cleanup must not make a corrupt cache entry readable.
            }
            null
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun put(key: Key, value: Entry) {
        if (value.body.size > Wire.MAX_FRAME) throw IOException("column too large")
        val target = path(key)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "column-", ".tmp")
        try {
            DataOutputStream(GZIPOutputStream(Files.newOutputStream(temporary))).use { output ->
                output.writeInt(0x41524c31)
                output.writeLong(value.timestamp)
                output.writeInt(value.body.size)
                output.write(value.body)
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun invalidate(key: Key) {
        Files.deleteIfExists(path(key))
    }

    @Synchronized
    @Throws(IOException::class)
    fun entries(): List<CacheRetention.Entry> =
        Files.walk(root).use { paths ->
            paths
                .filter { it.toString().endsWith(".gz") }
                .toList()
                .mapNotNull { path ->
                    val world =
                        try {
                            UUID.fromString(path.parent.fileName.toString())
                        } catch (_: IllegalArgumentException) {
                            return@mapNotNull null
                        }
                    CacheRetention.Entry(
                        "voxy:$world:${path.fileName}",
                        world,
                        Files.size(path),
                        Files.getLastModifiedTime(path).toMillis(),
                    )
                }
        }

    @Synchronized
    @Throws(IOException::class)
    fun evict(entry: CacheRetention.Entry) {
        val parts = entry.id.split(':', limit = 3)
        require(
            parts.size == 3 && parts[0] == "voxy" && parts[2].matches(Regex("-?\\d+_-?\\d+\\.gz"))
        ) {
            "cache entry"
        }
        Files.deleteIfExists(root.resolve(entry.world.toString()).resolve(parts[2]))
    }

    @Synchronized
    @Throws(IOException::class)
    fun trim() {
        if (maxBytes < 0) return
        val paths =
            Files.walk(root).use { walk ->
                walk
                    .filter { it.toString().endsWith(".gz") }
                    .toList()
                    .sortedBy {
                        try {
                            Files.getLastModifiedTime(it).toMillis()
                        } catch (_: IOException) {
                            0L
                        }
                    }
            }
        var total = paths.sumOf(Files::size)
        for (path in paths) {
            if (total <= maxBytes) break
            val size = Files.size(path)
            Files.deleteIfExists(path)
            total -= size
        }
    }
}
