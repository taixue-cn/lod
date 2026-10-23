package cc.taixue.lod.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionGateTest {
    @Test
    fun worldRevocationAndReconnectRetireEveryPriorLease() {
        val gate = SessionGate()
        val player = UUID.randomUUID()
        val firstWorld = UUID.randomUUID()
        val secondWorld = UUID.randomUUID()
        val first = gate.observe(player, firstWorld, true)
        assertTrue(gate.valid(first))
        assertNull(gate.observe(player, firstWorld, false))
        assertFalse(gate.valid(first))
        val granted = gate.observe(player, firstWorld, true)
        assertFalse(gate.valid(first))
        val changed = gate.observe(player, secondWorld, true)
        assertFalse(gate.valid(granted))
        assertTrue(gate.valid(changed))
        gate.observe(player, firstWorld, true)
        assertFalse(gate.valid(first))
        gate.remove(player)
        gate.observe(player, firstWorld, true)
        assertFalse(gate.valid(first))
    }

    @Test
    fun playersNeverShareLeases() {
        val gate = SessionGate()
        val world = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val lease = gate.observe(first, world, true)
        gate.observe(second, world, false)
        assertTrue(gate.valid(lease))
    }
}
