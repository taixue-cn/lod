package cc.taixue.lod.server

/** Shares tick health with background readers without accessing Bukkit from their threads. */
internal class PrewarmBudget(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(java.time.Duration.ofNanos(it)) },
) {
    private val ticks = LongArray(100)
    private var cursor = 0
    private var samples = 0
    @Volatile
    var paused = true
        private set

    @Volatile
    var tps = 0.0
        private set

    @Volatile private var duty = 25

    /**
     * Called on the main thread; the rolling window measures elapsed wall time, including stalls.
     */
    fun tick(minTps: Double, mspt: Double, maxMspt: Double, dutyPercent: Int) {
        val now = clock()
        val previous = ticks[cursor]
        ticks[cursor] = now
        cursor = (cursor + 1) % ticks.size
        if (samples < ticks.size) samples++
        else tps = minOf(20.0, ticks.size * 1_000_000_000.0 / (now - previous).coerceAtLeast(1))
        duty = dutyPercent.coerceIn(1, 100)
        paused = tps < minTps.coerceIn(1.0, 20.0) || mspt > maxMspt.coerceIn(1.0, 50.0)
    }

    /**
     * Each reader owns a slice; cancellation is checked even while TPS protection is holding it.
     */
    fun reader(cancelled: () -> Boolean): Slice = Slice(cancelled)

    inner class Slice(private val cancelled: () -> Boolean) {
        private var started = clock()

        fun checkpoint() {
            checkCancelled()
            var waited = false
            while (paused) {
                sleep(50_000_000)
                checkCancelled()
                waited = true
            }
            if (waited) started = clock()
            val elapsed = clock() - started
            if (elapsed < 2_000_000) return
            val percent = duty
            var remaining = elapsed * (100 - percent) / percent
            while (remaining > 0) {
                val step = minOf(remaining, 50_000_000)
                sleep(step)
                checkCancelled()
                remaining -= step
            }
            started = clock()
        }

        private fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
    }
}
