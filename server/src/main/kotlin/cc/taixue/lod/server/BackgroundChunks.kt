package cc.taixue.lod.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Prevent transient cache loads/unloads from recursively scheduling more cache work. */
object BackgroundChunks {
    private data class Key(val world: UUID, val x: Int, val z: Int)

    private val loaded = ConcurrentHashMap<Key, Long>()

    @JvmStatic
    fun loaded(world: UUID, x: Int, z: Int) {
        loaded[Key(world, x, z)] = System.currentTimeMillis()
    }

    @JvmStatic
    fun unloaded(world: UUID, x: Int, z: Int): Boolean {
        val timestamp = loaded.remove(Key(world, x, z)) ?: return false
        return System.currentTimeMillis() - timestamp < 600_000
    }

    @JvmStatic
    fun changed(world: UUID, x: Int, z: Int) {
        loaded.remove(Key(world, x, z))
    }

    @JvmStatic
    fun clear() {
        loaded.clear()
    }
}
