package cc.taixue.lod.core

import java.util.BitSet

/** Coverage of saved terrain; region bitsets keep large inventories compact across pauses. */
class CacheCoverage {
    /** Owned by one inventory worker until handed to coverage; no per-column boxed keys remain. */
    class Inventory {
        private val regions = mutableMapOf<Long, BitSet>()
        var size = 0L
            private set

        private fun region(position: Long) =
            Wire.pack(Math.floorDiv(Wire.x(position), 32), Math.floorDiv(Wire.z(position), 32))

        private fun slot(position: Long) =
            Math.floorMod(Wire.x(position), 32) * 32 + Math.floorMod(Wire.z(position), 32)

        operator fun contains(position: Long): Boolean =
            regions[region(position)]?.get(slot(position)) == true

        fun add(position: Long) {
            val bits = regions.getOrPut(region(position)) { BitSet(1024) }
            val slot = slot(position)
            if (!bits[slot]) {
                bits.set(slot)
                size++
            }
        }

        fun remove(position: Long) {
            val bits = regions[region(position)] ?: return
            val slot = slot(position)
            if (bits[slot]) {
                bits.clear(slot)
                size--
            }
        }

        internal fun intersectionSize(other: Inventory): Long =
            regions.entries.sumOf { (key, bits) ->
                val matching = other.regions[key] ?: return@sumOf 0L
                (bits.clone() as BitSet).apply { and(matching) }.cardinality().toLong()
            }
    }

    private val observed = Inventory()
    private val cached = Inventory()
    private var eligible: Inventory? = null
    @Volatile
    var cachedChunks = 0L
        private set

    @Volatile
    var totalChunks: Long? = null
        private set

    @Synchronized
    fun observe(position: Long, version: Long, seed: Boolean = false) {
        if (seed && position in observed) return
        observed.add(position)
        val before = position in cached
        val after = version > 0
        if (after) cached.add(position) else cached.remove(position)
        if (eligible?.contains(position) != false && before != after)
            cachedChunks += if (after) 1 else -1
    }

    fun inventory(positions: Set<Long>) = inventory(Inventory().apply { positions.forEach(::add) })

    @Synchronized
    fun inventory(positions: Inventory) {
        eligible = positions
        cachedChunks = cached.intersectionSize(positions)
        totalChunks = positions.size
    }
}
