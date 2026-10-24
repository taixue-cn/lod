package cc.taixue.lod.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class CachePurgeStateTest {
    @TempDir lateinit var root: Path

    @Test
    fun acknowledgementSurvivesRestartAndCannotMoveBackwards() {
        val file = root.resolve("purges.properties")
        val world = UUID.randomUUID()
        val state = CachePurgeState(file)
        assertEquals(0L, state.revision(world))
        state.completed(world, 7)
        state.completed(world, 3)
        assertEquals(7L, CachePurgeState(file).revision(world))
        assertEquals(0L, state.revision(UUID.randomUUID()))
    }

    @Test
    fun failedPersistenceDoesNotAcknowledgeAnErasure() {
        val parent = Files.writeString(root.resolve("not-a-directory"), "fixture")
        val world = UUID.randomUUID()
        val state = CachePurgeState(parent.resolve("purges.properties"))
        assertThrows<java.io.IOException> { state.completed(world, 7) }
        assertEquals(0L, state.revision(world))
    }
}
