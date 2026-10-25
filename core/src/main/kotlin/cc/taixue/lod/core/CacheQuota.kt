package cc.taixue.lod.core

import java.io.IOException
import java.util.UUID

/** Shared persisted-payload budget. Full caches keep their data and stop admitting growth. */
class CacheQuota {
    class Full(message: String) : IOException(message)

    private var maximum = -1L
    private var limits = emptyMap<UUID, Long>()
    private val used = mutableMapOf<UUID, Long>()
    private val reserved = mutableMapOf<UUID, Long>()
    private var ready = false
    private var inflight = 0
    private var needed = 0L
    private val worldNeeded = mutableMapOf<UUID, Long>()

    @Synchronized
    fun configure(maximum: Long, limits: Map<UUID, Long>) {
        require(maximum >= -1 && limits.values.all { it >= -1 })
        this.maximum = maximum
        this.limits = limits.toMap()
    }

    /** Inventory runs before new writes; reads of committed cache data remain available. */
    @Synchronized
    fun beginInventory(): Boolean {
        ready = false
        return inflight == 0
    }

    @Synchronized
    fun initialize(bytes: Map<UUID, Long>) {
        check(inflight == 0)
        require(bytes.values.all { it >= 0 })
        used.clear()
        used.putAll(bytes)
        ready = true
    }

    @Synchronized
    fun pauseReason(): String? {
        if (!ready) return "storage inventory pending"
        val total = used.values.sum() + reserved.values.sum()
        if (maximum >= 0 && (total >= maximum || needed > maximum - total))
            return "global storage quota reached"
        for ((world, limit) in limits) {
            val size = (used[world] ?: 0) + (reserved[world] ?: 0)
            if (limit >= 0 && (size >= limit || (worldNeeded[world] ?: 0) > limit - size))
                return "world storage quota reached: $world"
        }
        return null
    }

    @Synchronized
    fun status(): String =
        "cache bytes=${used.values.sum()}, reserved=${reserved.values.sum()}, limit=$maximum, " +
            "${pauseReason() ?: "generation allowed"}; quota eviction disabled"

    /** Reservations span the physical commit, so parallel formats cannot spend the same space. */
    @Synchronized
    @Throws(Full::class)
    fun reserve(world: UUID, delta: Long): Reservation {
        if (!ready) throw Full("storage inventory pending")
        val growth = maxOf(0, delta)
        val total = used.values.sum() + reserved.values.sum()
        if (growth > 0 && maximum >= 0 && growth > maximum - total) {
            needed = maxOf(needed, growth)
            throw Full("global storage quota reached")
        }
        val limit = limits[world] ?: -1
        val size = (used[world] ?: 0) + (reserved[world] ?: 0)
        if (growth > 0 && limit >= 0 && growth > limit - size) {
            worldNeeded[world] = maxOf(worldNeeded[world] ?: 0, growth)
            throw Full("world storage quota reached: $world")
        }
        reserved.merge(world, growth, Long::plus)
        inflight++
        return Reservation(world, delta, growth)
    }

    @Synchronized
    fun removed(world: UUID, bytes: Long) {
        require(bytes >= 0)
        if (ready) used[world] = maxOf(0, (used[world] ?: 0) - bytes)
    }

    inner class Reservation
    internal constructor(
        private val world: UUID,
        private val delta: Long,
        private val growth: Long,
    ) : AutoCloseable {
        private var closed = false

        @JvmOverloads
        fun commit(actualDelta: Long = delta) =
            synchronized(this@CacheQuota) {
                check(!closed)
                require(actualDelta <= growth)
                used[world] = maxOf(0, (used[world] ?: 0) + actualDelta)
                release()
            }

        override fun close() =
            synchronized(this@CacheQuota) {
                if (!closed) release()
            }

        private fun release() {
            reserved[world] = (reserved[world] ?: 0) - growth
            inflight--
            closed = true
        }
    }
}
