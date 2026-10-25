package cc.taixue.lod.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackgroundLoadTest {
    private val healthy = BackgroundLoad.Sample(20.0, 10.0, 8.0, 40.0, 0.3)

    @Test
    fun pressurePausesImmediatelyAndRecoveryIsGradual() {
        val budget = BackgroundLoad("test", monitor = false)
        repeat(40) { budget.update(healthy) }
        assertEquals(100, budget.duty)
        budget.update(healthy.copy(cpu = 0.85))
        assertEquals(25, budget.duty)
        budget.update(healthy.copy(heapPercent = 71.0))
        assertEquals(0, budget.duty)
        repeat(9) { budget.update(healthy) }
        assertEquals(0, budget.duty)
        budget.update(healthy)
        assertEquals(25, budget.duty)
        repeat(30) { budget.update(healthy) }
        assertEquals(100, budget.duty)
    }

    @Test
    fun aStalledMainThreadOrHostPressureStopsBackgroundWork() {
        for (bad in
            listOf(
                healthy.copy(tickGapMillis = 1000.0),
                healthy.copy(tps = 12.0),
                healthy.copy(mspt = 60.0),
                healthy.copy(cpu = 0.99),
                healthy.copy(availableMiB = 512),
                healthy.copy(memoryStallPercent = 6.0),
            )) {
            val budget = BackgroundLoad("test", monitor = false)
            repeat(40) { budget.update(healthy) }
            budget.update(bad)
            assertEquals(0, budget.duty, bad.toString())
            repeat(9) { budget.update(healthy) }
            budget.update(bad)
            assertEquals(0, budget.duty)
        }
    }

    @Test
    fun pausedReadersCanBeCancelledWithoutWaitingForRecovery() {
        val budget = BackgroundLoad("test", monitor = false)
        assertThrows(InterruptedException::class.java) { budget.reader { true }.checkpoint() }
    }
}
