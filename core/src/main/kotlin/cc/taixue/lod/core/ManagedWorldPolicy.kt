package cc.taixue.lod.core

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** A short server-management lease; missing worlds and expired responses always deny terrain. */
class ManagedWorldPolicy(private val clock: () -> Long = System::nanoTime) {
    data class Entry(val revision: Long, val enabled: Boolean)

    private data class Snapshot(val worlds: Map<UUID, Entry>, val expiresAt: Long)

    private val snapshot = AtomicReference(Snapshot(emptyMap(), 0))

    fun replace(worlds: Map<UUID, Entry>, requestStarted: Long) {
        require(worlds.values.all { it.revision >= 0 && (!it.enabled || it.revision > 0) })
        snapshot.set(Snapshot(worlds.toMap(), requestStarted + LEASE_NANOS))
    }

    fun allows(world: UUID): Boolean {
        val current = snapshot.get()
        return current.worlds[world]?.enabled == true && clock() - current.expiresAt < 0
    }

    fun entry(world: UUID): Entry? = snapshot.get().worlds[world]

    fun clear() {
        snapshot.set(Snapshot(emptyMap(), 0))
    }

    companion object {
        const val LEASE_NANOS: Long = 30_000_000_000L
    }
}
