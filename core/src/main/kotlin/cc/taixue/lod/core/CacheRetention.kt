package cc.taixue.lod.core

import java.util.UUID

/** Shared Voxy/DH budget: evict lower priorities first, then older writes. */
object CacheRetention {
    @JvmRecord
    data class Entry(val id: String, val world: UUID, val bytes: Long, val timestamp: Long)

    /** An explicit -1 pins a world's entries; null inherits the global rolling budget. */
    @JvmRecord
    data class Rule(val maxBytes: Long?, val priority: Int) {
        init {
            require(maxBytes == null || maxBytes >= -1) { "limit below -1" }
        }
    }

    @JvmStatic
    fun victims(entries: List<Entry>, global: Long, rules: Map<UUID, Rule>): List<Entry> {
        require(global >= -1) { "limit below -1" }
        val ordered =
            entries.sortedWith(
                compareBy<Entry> { rules[it.world]?.priority ?: 0 }
                    .thenBy { it.timestamp }
                    .thenBy { it.id }
            )
        val sizes = mutableMapOf<UUID, Long>()
        var total = 0L
        for (entry in entries) {
            sizes.merge(entry.world, entry.bytes, Long::plus)
            total += entry.bytes
        }
        val victims = mutableListOf<Entry>()
        val removed = mutableSetOf<String>()
        for (entry in ordered) {
            val maximum = rules[entry.world]?.maxBytes ?: continue
            if (maximum >= 0 && sizes.getValue(entry.world) > maximum) {
                victims += entry
                removed += entry.id
                sizes.merge(entry.world, -entry.bytes, Long::plus)
                total -= entry.bytes
            }
        }
        if (global >= 0) {
            for (entry in ordered) {
                if (total <= global) break
                if (entry.id in removed || rules[entry.world]?.maxBytes == -1L) continue
                victims += entry
                total -= entry.bytes
            }
        }
        return victims
    }
}
