package cc.taixue.lod.core

/** Negotiated LodClient extension; ordinary LSS clients keep their original wire format. */
object TerrainBatch {
    const val CHANNEL = "taixue:lod_batch"
    const val CAPABILITY = 1 shl 16
    const val MAX_ITEMS = 64
    const val MAX_BYTES = 1_048_000

    /** Types: 0 = model, 1 = unchanged, 2 = terrain unavailable. */
    data class Item(
        val position: Long,
        val type: Int,
        val timestamp: Long = 0,
        val body: ByteArray = byteArrayOf(),
    )

    data class Frame(val world: String, val items: List<Item>)

    fun encode(world: String, items: List<Item>, maximumBytes: Int = MAX_BYTES): List<ByteArray> {
        val header = Wire.Writer().varInt(1).utf(world).result()
        require(world.toByteArray(Charsets.UTF_8).size <= 256)
        val frames = mutableListOf<ByteArray>()
        var parts = mutableListOf<ByteArray>()
        var size = header.size + 5
        fun flush() {
            if (parts.isEmpty()) return
            val output = Wire.Writer().raw(header).varInt(parts.size)
            parts.forEach(output::raw)
            frames += output.result()
            parts = mutableListOf()
            size = header.size + 5
        }
        for (item in items) {
            require(item.type in 0..2)
            val output = Wire.Writer().u8(item.type).i64(item.position)
            if (item.type == 0) output.i64(item.timestamp).bytes(item.body)
            val part = output.result()
            require(header.size + 5 + part.size <= maximumBytes) { "column too large for batch" }
            if (size + part.size > maximumBytes || parts.size == MAX_ITEMS) flush()
            parts += part
            size += part.size
        }
        flush()
        return frames
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size <= MAX_BYTES)
        val reader = Wire.Reader(bytes)
        require(reader.varInt() == 1) { "unsupported terrain batch" }
        val world = reader.utf(256)
        val count = reader.count(MAX_ITEMS)
        val items = mutableListOf<Item>()
        repeat(count) {
            val type = reader.u8()
            require(type in 0..2)
            val position = reader.i64()
            items +=
                if (type == 0) Item(position, type, reader.i64(), reader.bytes(MAX_BYTES))
                else Item(position, type)
        }
        reader.end()
        return Frame(world, items)
    }
}
