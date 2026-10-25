import com.fastasyncworldedit.core.configuration.Settings
import com.fastasyncworldedit.core.util.MemUtil
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val field = MemUtil::class.java.getDeclaredField("memory")
    field.isAccessible = true
    val flag = field.get(null) as AtomicBoolean
    val runtime = Runtime.getRuntime()
    check(runtime.totalMemory() < runtime.maxMemory())
    flag.set(true)
    Settings.settings().MAX_MEMORY_PERCENT = 1
    check(MemUtil.isMemoryLimited()) { "Real threshold must still block edits" }
    Settings.settings().MAX_MEMORY_PERCENT = 95
    check(!MemUtil.isMemoryLimited()) { "Recovered heap must clear the stale flag" }
    flag.set(true)
    check(MemUtil.isMemoryFree())
    flag.set(true)
    val before = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount }
    check(!MemUtil.isMemoryLimitedSlow())
    val after = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount }
    check(after == before) { "Healthy heap recovery must not force GC" }
    println(
        "PASS threshold preserved; shrunk-heap recovery clears the flag; recovered edits avoid forced GC"
    )
}
