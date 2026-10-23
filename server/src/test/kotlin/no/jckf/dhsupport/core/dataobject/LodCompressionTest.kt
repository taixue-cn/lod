package no.jckf.dhsupport.core.dataobject

import java.io.ByteArrayInputStream
import java.util.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.tukaani.xz.XZInputStream

class LodCompressionTest {
    @Test
    fun writesACompleteXzStreamIncludingTheEndMarker() {
        val input = ByteArray(100000).also { Random(42).nextBytes(it) }
        val lod = Lod(null, null, emptyList(), emptyList(), emptyList())
        XZInputStream(ByteArrayInputStream(lod.compress(input))).use {
            assertArrayEquals(input, it.readAllBytes())
        }
    }
}
