package cc.taixue.lod.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WireTest {
    @Test
    fun negativeCoordinatesAndVarintsRoundTrip() {
        for (number in intArrayOf(0, 127, 128, 16384, Int.MAX_VALUE, -1, Int.MIN_VALUE)) {
            val reader = Wire.Reader(Wire.Writer().varInt(number).result())
            assertEquals(number, reader.varInt())
            reader.end()
            assertEquals(number, Wire.x(Wire.pack(number, -97)))
            assertEquals(-97, Wire.z(Wire.pack(number, -97)))
        }
    }

    @Test
    fun hostileLengthsAndTrailingBytesAreRejected() {
        assertThrows<IllegalArgumentException> {
            Wire.Reader(byteArrayOf(-1, -1, -1, -1, 127)).varInt()
        }
        assertThrows<IllegalArgumentException> { Wire.Reader(byteArrayOf(127)).bytes(32) }
        assertThrows<IllegalArgumentException> { Wire.Reader(byteArrayOf(2)).bool() }
        assertThrows<IllegalArgumentException> { Wire.Reader(byteArrayOf(1)).end() }
        assertThrows<IllegalArgumentException> { Wire.Reader(byteArrayOf(5, 1)).utf(1024) }
    }
}
