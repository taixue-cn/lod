package cc.taixue.lod.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedWorldPolicyTest {
    @Test
    fun `unknown worlds and expired or delayed grants deny access`() {
        var now = 1_000L
        val policy = ManagedWorldPolicy { now }
        val world = UUID.randomUUID()
        assertFalse(policy.allows(world))
        policy.replace(mapOf(world to ManagedWorldPolicy.Entry(1, true)), now)
        assertTrue(policy.allows(world))
        assertFalse(policy.allows(UUID.randomUUID()))
        now += ManagedWorldPolicy.LEASE_NANOS
        assertFalse(policy.allows(world))
        policy.replace(mapOf(world to ManagedWorldPolicy.Entry(1, true)), 1_000L)
        assertFalse(policy.allows(world))
    }

    @Test
    fun `disable revokes an unexpired grant and caller mutation cannot expand it`() {
        val policy = ManagedWorldPolicy { 5L }
        val world = UUID.randomUUID()
        val entries = mutableMapOf(world to ManagedWorldPolicy.Entry(1, true))
        policy.replace(entries, 5L)
        val added = UUID.randomUUID()
        entries[added] = ManagedWorldPolicy.Entry(1, true)
        assertFalse(policy.allows(added))
        policy.replace(mapOf(world to ManagedWorldPolicy.Entry(2, false)), 5L)
        assertFalse(policy.allows(world))
        assertEquals(2L, policy.entry(world)?.revision)
        policy.clear()
        assertNull(policy.entry(world))
    }
}
