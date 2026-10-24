package cc.taixue.lod.core

/** Coverage of a saved-terrain inventory; refreshes and retries never count a column twice. */
class CacheCoverage {
    private val versions = mutableMapOf<Long, Long>()
    private var eligible: Set<Long>? = null
    @Volatile
    var cachedChunks = 0L
        private set

    @Volatile
    var totalChunks: Long? = null
        private set

    @Synchronized
    fun observe(position: Long, version: Long, seed: Boolean = false) {
        if (seed && position in versions) return
        val before = (versions[position] ?: 0L) > 0
        versions[position] = version
        if (eligible?.contains(position) != false) {
            val after = version > 0
            if (before != after) cachedChunks += if (after) 1 else -1
        }
    }

    @Synchronized
    fun inventory(positions: Set<Long>) {
        eligible = positions
        cachedChunks = versions.count { it.value > 0 && it.key in positions }.toLong()
        totalChunks = positions.size.toLong()
    }
}
