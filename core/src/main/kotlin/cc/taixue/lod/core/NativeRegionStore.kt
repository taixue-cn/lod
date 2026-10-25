package cc.taixue.lod.core

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.CRC32

/**
 * Derived native cache: 32×32 columns per file, CRC-checked records and a bounded region read
 * cache.
 */
class NativeRegionStore(private val root: Path, private val maxIndexedRegions: Int = 128) {
    init {
        require(maxIndexedRegions > 0)
    }

    private data class Record(val offset: Int, val length: Int, val version: Long, val crc: Long)

    private data class Region(val world: UUID, val x: Int, val z: Int)

    private val indexes =
        object : LinkedHashMap<Region, MutableMap<Long, Record>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Region, MutableMap<Long, Record>>
            ): Boolean = size > maxIndexedRegions
        }
    private var memoryCaching = true

    /** Drops only rebuildable RAM; persisted native and canonical terrain are untouched. */
    @Synchronized
    fun setMemoryCaching(enabled: Boolean) {
        memoryCaching = enabled
        if (!enabled) {
            indexes.clear()
            buffers.clear()
            buffered = 0
        }
    }

    private val buffers = linkedMapOf<Region, ByteArray>()
    private var buffered = 0L

    private fun region(key: ColumnCache.Key) =
        Region(key.world, Math.floorDiv(key.x, 32), Math.floorDiv(key.z, 32))

    private fun path(region: Region) =
        root.resolve(region.world.toString()).resolve("${region.x}_${region.z}.native")

    private fun index(region: Region): MutableMap<Long, Record> =
        indexes[region]
            ?: run {
                val records = mutableMapOf<Long, Record>()
                val file = path(region)
                if (Files.isRegularFile(file))
                    RandomAccessFile(file.toFile(), "rw").use { input ->
                        var end = 0L
                        while (input.filePointer + 28 <= input.length()) {
                            val position = input.readLong()
                            val version = input.readLong()
                            val length = input.readInt()
                            val crc = input.readLong()
                            if (
                                length !in 1..Wire.MAX_FRAME ||
                                    input.filePointer + length > input.length()
                            )
                                break
                            if (
                                Math.floorDiv(Wire.x(position), 32) != region.x ||
                                    Math.floorDiv(Wire.z(position), 32) != region.z
                            )
                                break
                            records[position] =
                                Record(input.filePointer.toInt(), length, version, crc)
                            input.seek(input.filePointer + length)
                            end = input.filePointer
                        }
                        // Only derived data is truncated after an interrupted append; original LSS
                        // cache survives.
                        if (end != input.length()) input.setLength(end)
                    }
                if (memoryCaching) indexes[region] = records
                records
            }

    @Synchronized
    fun get(key: ColumnCache.Key, version: Long): ByteArray? {
        val region = region(key)
        val record = index(region)[Wire.pack(key.x, key.z)] ?: return null
        if (record.version != version) return null
        if (!memoryCaching) {
            val file = path(region)
            if (!Files.isRegularFile(file)) return null
            val body = ByteArray(record.length)
            RandomAccessFile(file.toFile(), "r").use { input ->
                if (record.offset.toLong() + record.length > input.length()) return null
                input.seek(record.offset.toLong())
                input.readFully(body)
            }
            return body.takeIf { CRC32().apply { update(it) }.value == record.crc }
        }
        val bytes =
            buffers.remove(region)
                ?: run {
                    val file = path(region)
                    if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024 * 1024)
                        return null
                    Files.readAllBytes(file).also { if (memoryCaching) buffered += it.size }
                }
        if (memoryCaching) buffers[region] = bytes
        while (buffered > 64 * 1024 * 1024 && buffers.size > 1) {
            val first = buffers.keys.first()
            buffered -= buffers.remove(first)!!.size
        }
        if (record.offset.toLong() + record.length > bytes.size) return null
        val body = bytes.copyOfRange(record.offset, record.offset + record.length)
        if (CRC32().apply { update(body) }.value != record.crc) return null
        return body
    }

    @Synchronized
    fun put(key: ColumnCache.Key, version: Long, body: ByteArray) {
        require(body.size in 1..Wire.MAX_FRAME)
        val region = region(key)
        val records = index(region)
        val file = path(region)
        Files.createDirectories(file.parent)
        val crc = CRC32().apply { update(body) }.value
        RandomAccessFile(file.toFile(), "rw").use { output ->
            // Bound a frequently edited region. A discarded derived region is rebuilt on demand.
            if (output.length() > 32 * 1024 * 1024) {
                output.setLength(0)
                records.clear()
            }
            output.seek(output.length())
            output.writeLong(Wire.pack(key.x, key.z))
            output.writeLong(version)
            output.writeInt(body.size)
            output.writeLong(crc)
            records[Wire.pack(key.x, key.z)] =
                Record(output.filePointer.toInt(), body.size, version, crc)
            output.write(body)
        }
        buffers.remove(region)?.let { buffered -= it.size }
    }

    @JvmOverloads
    fun entries(checkpoint: () -> Unit = {}): List<CacheRetention.Entry> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { files ->
            files
                .filter { it.toString().endsWith(".native") }
                .map { file ->
                    checkpoint()
                    val world = UUID.fromString(file.parent.fileName.toString())
                    CacheRetention.Entry(
                        "native:$world:${file.fileName}",
                        world,
                        Files.size(file),
                        Files.getLastModifiedTime(file).toMillis(),
                    )
                }
                .toList()
        }
    }

    @Synchronized
    fun evict(entry: CacheRetention.Entry) {
        val name = entry.id.substringAfterLast(':')
        require(name.matches(Regex("-?\\d+_-?\\d+\\.native")))
        val coordinates = name.removeSuffix(".native").split('_').map(String::toInt)
        val region = Region(entry.world, coordinates[0], coordinates[1])
        Files.deleteIfExists(path(region))
        indexes.remove(region)
        buffers.remove(region)?.let { buffered -= it.size }
    }
}
