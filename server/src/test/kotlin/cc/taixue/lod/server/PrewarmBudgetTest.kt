package cc.taixue.lod.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrewarmBudgetTest {
    @Test
    fun `a wall clock stall pauses readers and healthy ticks resume them`() {
        var now = 1L
        val budget = PrewarmBudget({ now }, { fail("A healthy reader should not sleep") })
        repeat(101) {
            now += 50_000_000
            budget.tick(19.5, 10.0, 40.0, 100)
        }
        assertFalse(budget.paused)
        now += 1_000_000_000
        budget.tick(19.5, 10.0, 40.0, 100)
        assertTrue(budget.paused)
        assertTrue(budget.tps < 19.5)
        repeat(100) {
            now += 50_000_000
            budget.tick(19.5, 10.0, 40.0, 100)
        }
        assertFalse(budget.paused)
        budget.tick(19.5, 45.0, 40.0, 100)
        assertTrue(budget.paused)
    }

    @Test
    fun `reader duty changes apply without recreating the reader`() {
        var now = 1L
        var slept = 0L
        val budget =
            PrewarmBudget(
                { now },
                {
                    slept += it
                    now += it
                },
            )
        repeat(101) {
            now += 50_000_000
            budget.tick(19.5, 10.0, 40.0, 25)
        }
        val reader = budget.reader { false }
        now += 2_000_000
        reader.checkpoint()
        assertEquals(6_000_000L, slept)
        budget.tick(19.5, 10.0, 40.0, 50)
        now += 2_000_000
        reader.checkpoint()
        assertEquals(8_000_000L, slept)
    }

    @Test
    fun `heap pressure pauses background work even at twenty TPS`() {
        var now = 1L
        val budget = PrewarmBudget({ now }, { fail("No reader sleeps in this test") })
        repeat(101) {
            now += 50_000_000
            budget.tick(19.5, 10.0, 40.0, 25, 65.0, 70.0)
        }
        assertFalse(budget.paused)
        budget.tick(19.5, 10.0, 40.0, 25, 70.0, 70.0)
        assertTrue(budget.paused)
        budget.tick(19.5, 10.0, 40.0, 25, 60.0, 70.0)
        assertFalse(budget.paused)
    }

    @Test
    fun `paused readers observe cancellation instead of getting stuck`() {
        var cancelled = false
        val budget = PrewarmBudget({ 1L }, { cancelled = true })
        val reader = budget.reader { cancelled }
        assertThrows(InterruptedException::class.java) { reader.checkpoint() }
    }
}
