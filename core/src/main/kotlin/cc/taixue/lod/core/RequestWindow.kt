package cc.taixue.lod.core

/**
 * Bounds in-flight columns independently of view radius; overdue requests retry before new work.
 */
class RequestWindow(private val capacity: Int = 64, private val timeoutMillis: Long = 15000) {
    val pending = linkedMapOf<Long, Long>()
    val available: Int
        get() = capacity - pending.size

    fun claim(position: Long, now: Long): Boolean {
        if (available <= 0 || position in pending) return false
        pending[position] = now
        return true
    }

    fun retries(now: Long, maximum: Int): List<Long> {
        val result = mutableListOf<Long>()
        for (entry in pending.entries) {
            if (result.size >= maximum) break
            if (now - entry.value < timeoutMillis) continue
            result += entry.key
            entry.setValue(now)
        }
        return result
    }
}
