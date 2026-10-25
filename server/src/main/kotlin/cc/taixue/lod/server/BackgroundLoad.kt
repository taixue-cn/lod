package cc.taixue.lod.server

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Wall-clock monitoring stays responsive even while the Minecraft main thread is stalled. */
class BackgroundLoad(
    name: String,
    private val maxHeapPercent: Double = 70.0,
    monitor: Boolean = true,
    private val storagePressure: () -> String? = { null },
) : AutoCloseable {
    data class Sample(
        val tps: Double,
        val tickGapMillis: Double,
        val mspt: Double,
        val heapPercent: Double,
        val cpu: Double,
        val availableMiB: Long = Long.MAX_VALUE,
        val memoryStallPercent: Double = 0.0,
    )

    val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val ticks = AtomicLong()
    @Volatile private var lastTick = System.nanoTime()
    @Volatile private var mspt = 0.0
    @Volatile
    var duty = 0
        private set

    @Volatile
    var reason = "waiting for healthy server samples"
        private set

    private var healthy = 0
    private var previousTime = System.nanoTime()
    private var previousTicks = 0L
    private val system = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private val worker =
        if (monitor)
            Executors.newSingleThreadScheduledExecutor {
                    Thread(it, name).apply {
                        isDaemon = true
                        priority = Thread.MIN_PRIORITY
                    }
                }
                .also { it.scheduleWithFixedDelay(::sample, 1, 1, TimeUnit.SECONDS) }
        else null

    /** Only this lightweight heartbeat reads server tick statistics, on the main thread. */
    fun heartbeat(averageTickMillis: Double) {
        mspt = averageTickMillis
        lastTick = System.nanoTime()
        ticks.incrementAndGet()
    }

    private fun sample() {
        try {
            val now = System.nanoTime()
            val count = ticks.get()
            val runtime = Runtime.getRuntime()
            val available = runCatching {
                Files.readAllLines(Path.of("/proc/meminfo"))
                    .first { it.startsWith("MemAvailable:") }
                    .substringAfter(':')
                    .trim()
                    .substringBefore(' ')
                    .toLong() / 1024
            }
                .getOrDefault(Long.MAX_VALUE)
            val stalls = runCatching {
                Files.readAllLines(Path.of("/proc/pressure/memory"))
                    .first { it.startsWith("some ") }
                    .substringAfter("avg10=")
                    .substringBefore(' ')
                    .toDouble()
            }
                .getOrDefault(0.0)
            update(
                Sample(
                    (count - previousTicks) * 1_000_000_000.0 /
                        (now - previousTime).coerceAtLeast(1),
                    (now - lastTick) / 1_000_000.0,
                    mspt,
                    (runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory(),
                    system?.cpuLoad ?: -1.0,
                    available,
                    stalls,
                )
            )
            previousTime = now
            previousTicks = count
        } catch (_: Exception) {
            duty = 0
            healthy = 0
            reason = "load monitoring unavailable"
        }
    }

    /**
     * Reduce immediately; recover one step only after ten consecutive stable wall-clock samples.
     */
    @Synchronized
    internal fun update(value: Sample) {
        val wasPaused = duty == 0
        reason =
            storagePressure()
                ?: when {
                    value.tickGapMillis > 250 || value.tps < 18.5 || value.mspt >= 45 ->
                        "tick pressure"
                    value.heapPercent >= maxHeapPercent -> "heap pressure"
                    value.availableMiB < 768 || value.memoryStallPercent >= 5 ->
                        "host memory pressure"
                    value.cpu >= 0.95 -> "host CPU pressure"
                    else -> ""
                }
        val target =
            when {
                reason.isNotEmpty() -> 0
                value.tps < 19.5 ||
                    value.mspt >= 30 ||
                    value.heapPercent >= maxHeapPercent - 10 ||
                    value.cpu >= 0.80 ||
                    value.availableMiB < 1536 ||
                    value.memoryStallPercent >= 1 -> 25
                value.cpu >= 0.65 || value.heapPercent >= maxHeapPercent - 15 -> 50
                else -> 100
            }
        if (target <= duty) {
            duty = target
            healthy = 0
        } else if (++healthy >= 10) {
            duty = minOf(target, duty + 25)
            healthy = 0
        }
        if (reason.isEmpty()) reason = if (duty == 100) "healthy" else "recovering or throttled"
        if (wasPaused != (duty == 0)) stateListeners.forEach { it(duty > 0) }
    }

    /** A worker yields between columns/records and holds at most its current bounded unit. */
    fun reader(cancelled: () -> Boolean): Slice = Slice(cancelled)

    inner class Slice(private val cancelled: () -> Boolean) {
        private var started = System.nanoTime()

        fun checkpoint() {
            var waited = false
            while (duty == 0) {
                checkActive()
                Thread.sleep(100)
                waited = true
            }
            checkActive()
            if (waited) started = System.nanoTime()
            val elapsed = System.nanoTime() - started
            if (elapsed < 2_000_000) return
            val percent = duty.coerceAtLeast(1)
            var rest = elapsed * (100 - percent) / percent
            while (rest > 0) {
                checkActive()
                val step = minOf(rest, 50_000_000)
                TimeUnit.NANOSECONDS.sleep(step)
                rest -= step
            }
            started = System.nanoTime()
        }

        private fun checkActive() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
    }

    override fun close() {
        worker?.shutdownNow()
    }
}
