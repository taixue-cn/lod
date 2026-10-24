package cc.taixue.lod.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestWindowTest {
    @Test
    fun thousandChunkRadiusFitsAndStillHasAnUpperBound() {
        assertEquals(1000, LodLimits.DEFAULT_RADIUS_CHUNKS)
        assertEquals(1000, LodLimits.radius(1000))
        assertEquals(1024, LodLimits.radius(Int.MAX_VALUE))
        assertEquals(8, LodLimits.radius(-1))
        val width = LodLimits.MAX_RADIUS_CHUNKS * 2 + 1
        assertEquals(4_198_401, width * width)
    }

    @Test
    fun scanningMillionsOfPositionsCannotGrowTheInflightWindow() {
        val window = RequestWindow()
        var claimed = 0
        for (position in 0L..4_004_000L) if (window.claim(position, 100)) claimed++
        assertEquals(64, claimed)
        assertEquals(64, window.pending.size)
        assertEquals(0, window.available)
    }

    @Test
    fun timedOutRequestsRetryInBatchesWithoutReleasingTheirSlots() {
        val window = RequestWindow(3, 100)
        for (position in 1L..3L) assertTrue(window.claim(position, 0))
        assertTrue(window.retries(99, 2).isEmpty())
        assertEquals(listOf(1L, 2L), window.retries(100, 2))
        assertEquals(listOf(3L), window.retries(100, 2))
        assertTrue(window.retries(101, 2).isEmpty())
        assertFalse(window.claim(4, 101))
        window.pending.remove(2)
        assertTrue(window.claim(4, 101))
    }
}
