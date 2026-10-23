package cc.taixue.lod.core

import java.util.UUID

/** Main-thread-owned leases invalidate queued work on world, permission or connection changes. */
class SessionGate {
    @JvmRecord data class Lease(val player: UUID, val world: UUID, val epoch: Long)

    private data class State(val world: UUID, val epoch: Long, val allowed: Boolean)

    private val states = mutableMapOf<UUID, State>()
    private var nextEpoch = 0L

    fun observe(player: UUID, world: UUID, allowed: Boolean): Lease? {
        var state = states[player]
        if (state == null || state.world != world || state.allowed != allowed) {
            state = State(world, ++nextEpoch, allowed)
            states[player] = state
        }
        return if (allowed) Lease(player, world, state.epoch) else null
    }

    fun valid(lease: Lease?): Boolean {
        if (lease == null) return false
        val state = states[lease.player] ?: return false
        return state.allowed && state.epoch == lease.epoch && state.world == lease.world
    }

    fun remove(player: UUID) {
        states.remove(player)
    }

    fun clear() {
        states.clear()
    }
}
