package cc.taixue.lod.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.UncheckedIOException

/** Plugin-message primitives. Validate lengths before allocating untrusted payloads. */
object Wire {
    const val MAX_FRAME = 1_048_576

    @JvmStatic fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

    @JvmStatic fun x(position: Long): Int = (position shr 32).toInt()

    @JvmStatic fun z(position: Long): Int = position.toInt()

    class Reader(bytes: ByteArray) {
        private val input: DataInputStream

        init {
            require(bytes.size <= MAX_FRAME) { "frame too large" }
            input = DataInputStream(ByteArrayInputStream(bytes))
        }

        private fun <T> read(block: () -> T): T =
            try {
                block()
            } catch (cause: IOException) {
                throw malformed(cause)
            }

        fun remaining(): Int = read { input.available() }

        fun varInt(): Int {
            var value = 0
            for (shift in 0 until 35 step 7) {
                val byte = u8()
                if (shift == 28 && byte and 0xf0 != 0) throw malformed()
                value = value or ((byte and 127) shl shift)
                if (byte and 128 == 0) return value
            }
            throw malformed()
        }

        fun count(maximum: Int): Int =
            varInt().also {
                if (it < 0 || it > maximum) throw malformed()
            }

        fun u8(): Int = read { input.readUnsignedByte() }

        fun bool(): Boolean =
            when (u8()) {
                0 -> false
                1 -> true
                else -> throw malformed()
            }

        fun i32(): Int = read { input.readInt() }

        fun i64(): Long = read { input.readLong() }

        fun raw(length: Int): ByteArray {
            if (length < 0 || length > remaining()) throw malformed()
            return read { input.readNBytes(length) }
        }

        fun bytes(maximum: Int): ByteArray = raw(count(maximum))

        fun utf(maximumBytes: Int): String = bytes(maximumBytes).toString(Charsets.UTF_8)

        fun end() {
            if (remaining() != 0) throw malformed()
        }

        private fun malformed(cause: Exception? = null) =
            IllegalArgumentException("Malformed LOD message", cause)
    }

    class Writer {
        private val buffer = ByteArrayOutputStream()
        private val output = DataOutputStream(buffer)

        private fun put(block: () -> Unit): Writer = apply {
            try {
                block()
            } catch (cause: IOException) {
                throw UncheckedIOException(cause)
            }
        }

        fun varInt(number: Int): Writer = apply {
            var value = number
            do {
                val byte = value and 127
                value = value ushr 7
                u8(if (value == 0) byte else byte or 128)
            } while (value != 0)
        }

        fun u8(value: Int): Writer = put { output.writeByte(value) }

        fun bool(value: Boolean): Writer = u8(if (value) 1 else 0)

        fun i32(value: Int): Writer = put { output.writeInt(value) }

        fun i64(value: Long): Writer = put { output.writeLong(value) }

        fun raw(value: ByteArray): Writer = put { output.write(value) }

        fun bytes(value: ByteArray): Writer = varInt(value.size).raw(value)

        fun utf(value: String): Writer = bytes(value.toByteArray(Charsets.UTF_8))

        fun result(): ByteArray = buffer.toByteArray()
    }
}
