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

    val listeners = java.util.concurrent.CopyOnWriteArrayList<(Key, Long) -> Unit>()

    @Volatile var changed: (Key, Long) -> Unit = { _, _ -> }

    private fun publish(key: Key, version: Long) {
        changed(key, version)
        listeners.forEach { it(key, version) }
    }

    /** File versions are opaque nanosecond values, independent of client clocks. */
    fun version(key: Key): Long =
        try {
            val file = path(key)
            if (!Files.isRegularFile(file)) 0L
            else Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS)
        } catch (_: IOException) {
            0L
        }

    @JvmOverloads
    fun catalog(world: UUID? = null): List<Pair<Key, Long>> = buildList {
        forEachCached(world) { key, version -> add(key to version) }
    }

    /** Stream inventory without retaining every file path and cache entry while readers pause. */
    fun forEachCached(
        world: UUID? = null,
        checkpoint: () -> Unit = {},
        consumer: (Key, Long) -> Unit,
    ) {
        val directory = world?.let { root.resolve(it.toString()) } ?: root
        if (!Files.isDirectory(directory)) return
        Files.walk(directory).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                checkpoint()
                val file = iterator.next()
                if (!file.toString().endsWith(".gz")) continue
                val key =
                    try {
                        val coordinates = file.fileName.toString().removeSuffix(".gz").split('_')
                        if (coordinates.size != 2) continue
                        Key(
                            UUID.fromString(file.parent.fileName.toString()),
                            coordinates[0].toInt(),
                            coordinates[1].toInt(),
                        )
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                consumer(key, version(key))
            }
        }
    }

    private val maxAgeMillis = maxAge.toMillis()

    init {
        Files.createDirectories(root)
    }

    private fun path(key: Key): Path =
        root.resolve(key.world.toString()).resolve("${key.x}_${key.z}.gz")

    /**
     * Cheap existence/freshness check for atomic server-written files; get still validates the full
     * payload.
     */
    fun contains(key: Key): Boolean =
        try {
            val file = path(key)
            val attributes =
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
            if (
                !attributes.isRegularFile ||
                    attributes.size() < 18 ||
                    (maxAgeMillis >= 0 &&
                        System.currentTimeMillis() - attributes.lastModifiedTime().toMillis() >
                            maxAgeMillis)
            )
                false
            else
                DataInputStream(GZIPInputStream(Files.newInputStream(file))).use { input ->
                    input.readInt() == 0x41524c31 &&
                        input.readLong() >= 0 &&
                        input.readInt() in 0..Wire.MAX_FRAME
                }
        } catch (_: IOException) {
            false
        }

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
                publish(key, 0)
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
            publish(key, version(key))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun invalidate(key: Key) {
        Files.deleteIfExists(path(key))
        publish(key, 0)
    }

    @JvmOverloads
    @Throws(IOException::class)
    fun entries(checkpoint: () -> Unit = {}): List<CacheRetention.Entry> = buildList {
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                checkpoint()
                val path = iterator.next()
                if (!path.toString().endsWith(".gz")) continue
                val world =
                    try {
                        UUID.fromString(path.parent.fileName.toString())
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                val attributes =
                    try {
                        Files.readAttributes(
                            path,
                            java.nio.file.attribute.BasicFileAttributes::class.java,
                        )
                    } catch (_: java.nio.file.NoSuchFileException) {
                        continue
                    }
                add(
                    CacheRetention.Entry(
                        "voxy:$world:${path.fileName}",
                        world,
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                    )
                )
            }
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
        val coordinates = parts[2].removeSuffix(".gz").split('_').map(String::toInt)
        publish(Key(entry.world, coordinates[0], coordinates[1]), 0)
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
            val coordinates =
                path.fileName.toString().removeSuffix(".gz").split('_').map(String::toInt)
            publish(
                Key(
                    UUID.fromString(path.parent.fileName.toString()),
                    coordinates[0],
                    coordinates[1],
                ),
                0,
            )
            total -= size
        }
    }
}
