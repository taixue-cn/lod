package cc.taixue.lod.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CacheRetentionTest {
    @Test
    fun combinedBudgetsPriorityAgeAndPinnedWorlds() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val pinnedWorld = UUID.randomUUID()
        val old = CacheRetention.Entry("voxy:a", first, 30, 1)
        val newer = CacheRetention.Entry("dh:a", first, 30, 2)
        val high = CacheRetention.Entry("dh:b", second, 60, 0)
        val pinned = CacheRetention.Entry("voxy:p", pinnedWorld, 90, 0)
        val entries = listOf(old, newer, high, pinned)
        val rules =
            mapOf(
                first to CacheRetention.Rule(40, 0),
                second to CacheRetention.Rule(null, 10),
                pinnedWorld to CacheRetention.Rule(-1, -100),
            )
        assertEquals(listOf(old), CacheRetention.victims(entries, -1, rules))
        assertEquals(listOf(old, newer), CacheRetention.victims(entries, 150, rules))
        assertEquals(listOf(old, newer, high), CacheRetention.victims(entries, 0, rules))
        assertTrue(CacheRetention.victims(entries, -1, emptyMap()).isEmpty())
        assertThrows<IllegalArgumentException> { CacheRetention.victims(entries, -2, rules) }
    }
}
