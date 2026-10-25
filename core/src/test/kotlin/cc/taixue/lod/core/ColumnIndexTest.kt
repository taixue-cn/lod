package cc.taixue.lod.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ColumnIndexTest {
    @Test
    fun deletedVersionsAreNotResurrectedByAnOlderInventory() {
        val index = ColumnIndex()
        val key = ColumnCache.Key(UUID.randomUUID(), -1, -33)
        assertNull(index[key])
        index[key] = 0
        index.putIfAbsent(key, 10)
        assertEquals(0L, index[key])
        assertTrue(index.nearby(key.world, -1, -33, 10).none())
        index[key] = 20
        assertEquals(listOf(key to 20L), index.nearby(key.world, -1, -33, 0).toList())
    }

    @Test
    fun columnsAreScopedByWorldAndExactRangeAcrossNegativeRegionBoundaries() {
        val index = ColumnIndex()
        val world = UUID.randomUUID()
        val expected = mutableSetOf<Pair<ColumnCache.Key, Long>>()
        for (x in -34..34) for (z in -34..34) {
            val key = ColumnCache.Key(world, x, z)
            index[key] = 1
            if (x in -33..-29 && z in -33..-29) expected += key to 1L
        }
        index[ColumnCache.Key(UUID.randomUUID(), -31, -31)] = 2
        assertEquals(expected, index.nearby(world, -31, -31, 2).toSet())
    }
}
