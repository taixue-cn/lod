package cc.taixue.lod.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Streams occupied Anvil slots without creating or loading a Minecraft chunk. */
class SavedChunks @Throws(IOException::class) constructor(region: Path) {
    @JvmRecord data class Chunk(val x: Int, val z: Int, val regionX: Int, val regionZ: Int)

    private val name = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")
    private val files =
        if (Files.isDirectory(region)) {
            Files.list(region).use { paths ->
                paths.filter { name.matches(it.fileName.toString()) }.sorted().toList().iterator()
            }
        } else {
            emptyList<Path>().iterator()
        }
    private var locations = IntArray(0)
    private var cursor = 0
    private var regionX = 0
    private var regionZ = 0
    private lateinit var regionFile: Path

    @Throws(IOException::class)
    fun next(): Chunk? {
        while (true) {
            while (cursor < locations.size) {
                val index = cursor++
                if (locations[index] == 0) continue
                val chunk =
                    Chunk(
                        Math.addExact(Math.multiplyExact(regionX, 32), index and 31),
                        Math.addExact(Math.multiplyExact(regionZ, 32), index shr 5),
                        regionX,
                        regionZ,
                    )
                if (full(regionFile, locations[index], chunk)) return chunk
            }
            if (!files.hasNext()) return null
            val path = files.next()
            regionFile = path
            val match = name.matchEntire(path.fileName.toString())!!
            regionX = match.groupValues[1].toInt()
            regionZ = match.groupValues[2].toInt()
            locations = IntArray(1024)
            cursor = 0
            try {
                DataInputStream(Files.newInputStream(path)).use { input ->
                    val sectors = Files.size(path) / 4096
                    for (index in locations.indices) {
                        val value = input.readInt()
                        val offset = value ushr 8
                        val count = value and 255
                        if (offset >= 2 && count > 0 && offset.toLong() + count <= sectors)
                            locations[index] = value
                    }
                }
            } catch (_: EOFException) {
                locations = IntArray(0)
            }
        }
    }

    // LZ4 delegates to Paper's generated-status guard. Known formats reject proto chunks on the I/O
    // worker.
    private fun full(path: Path, location: Int, chunk: Chunk): Boolean =
        try {
            RandomAccessFile(path.toFile(), "r").use { file ->
                file.seek((location ushr 8).toLong() * 4096)
                val size = file.readInt()
                var type = file.readUnsignedByte()
                if (size < 1 || size > (location and 255) * 4096 - 4) return false
                val payload =
                    if (type and 128 != 0) {
                        val external = path.parent.resolve("c.${chunk.x}.${chunk.z}.mcc")
                        if (Files.size(external) > MAX_NBT_BYTES) return false
                        Files.readAllBytes(external)
                    } else {
                        ByteArray(size - 1).also(file::readFully)
                    }
                type = type and 127
                if (type == 4) return true
                if (type !in 1..3) return false
                val raw = ByteArrayInputStream(payload)
                val decoded =
                    when (type) {
                        1 -> GZIPInputStream(raw)
                        2 -> InflaterInputStream(raw)
                        else -> raw
                    }
                DataInputStream(
                        object : FilterInputStream(decoded) {
                            var remaining = MAX_NBT_BYTES

                            override fun read(): Int {
                                if (--remaining < 0) throw IOException("NBT too large")
                                return super.read()
                            }

                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                if (remaining <= 0) throw IOException("NBT too large")
                                val count = super.read(bytes, offset, minOf(remaining, length))
                                if (count > 0) remaining -= count
                                return count
                            }

                            override fun skip(length: Long): Long {
                                val buffer = ByteArray(minOf(8192L, length).toInt())
                                var total = 0L
                                while (total < length) {
                                    val count =
                                        read(
                                            buffer,
                                            0,
                                            minOf(buffer.size.toLong(), length - total).toInt(),
                                        )
                                    if (count < 0) break
                                    total += count
                                }
                                return total
                            }
                        }
                    )
                    .use { input ->
                        if (input.readUnsignedByte() != 10) return false
                        input.readUTF()
                        compound(input, 0) == true
                    }
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun compound(input: DataInputStream, depth: Int): Boolean? {
        if (depth > 64) throw IOException("NBT depth")
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) return null
            val name = input.readUTF()
            if (type == 8 && name == "Status")
                return input.readUTF() in setOf("full", "minecraft:full")
            if (type == 10 && name == "Level") {
                compound(input, depth + 1)?.let {
                    return it
                }
            } else {
                skip(input, type, depth + 1)
            }
        }
    }

    private fun length(input: DataInputStream): Int =
        input.readInt().also {
            if (it !in 0..MAX_NBT_BYTES) throw IOException("NBT length")
        }

    private fun skip(input: DataInputStream, type: Int, depth: Int) {
        if (depth > 64) throw IOException("NBT depth")
        when (type) {
            1 -> input.skipNBytes(1)
            2 -> input.skipNBytes(2)
            3,
            5 -> input.skipNBytes(4)
            4,
            6 -> input.skipNBytes(8)
            7 -> input.skipNBytes(length(input).toLong())
            8 -> input.readUTF()
            9 -> {
                val child = input.readUnsignedByte()
                repeat(length(input)) { skip(input, child, depth + 1) }
            }
            10 ->
                while (true) {
                    val child = input.readUnsignedByte()
                    if (child == 0) break
                    input.readUTF()
                    skip(input, child, depth + 1)
                }
            11 -> input.skipNBytes(length(input).toLong() * 4)
            12 -> input.skipNBytes(length(input).toLong() * 8)
            else -> throw IOException("NBT tag")
        }
    }

    private companion object {
        const val MAX_NBT_BYTES = 33_554_432
    }
}
