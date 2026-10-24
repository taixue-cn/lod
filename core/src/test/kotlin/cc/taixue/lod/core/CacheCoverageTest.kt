package cc.taixue.lod.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CacheCoverageTest {
    @Test
    fun countsUniqueCoverageAndRemovesEvictedColumns() {
        val coverage = CacheCoverage()
        coverage.observe(1, 10)
        coverage.observe(1, 20)
        coverage.observe(99, 10)
        assertNull(coverage.totalChunks)
        coverage.inventory(setOf(1L, 2L, 3L))
        assertEquals(3L, coverage.totalChunks)
        assertEquals(1L, coverage.cachedChunks)
        coverage.observe(2, 30)
        assertEquals(2L, coverage.cachedChunks)
        coverage.observe(1, 0)
        assertEquals(1L, coverage.cachedChunks)
    }

    @Test
    fun aStaleInitialInventoryCannotResurrectAnEviction() {
        val coverage = CacheCoverage()
        coverage.observe(1, 0)
        coverage.observe(1, 10, seed = true)
        coverage.inventory(setOf(1L))
        assertEquals(0L, coverage.cachedChunks)
    }
}
