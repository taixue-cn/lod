package cc.taixue.lod.server

import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit

/** Registry-only immutable state lookup; no world or chunk access occurs on cache workers. */
internal class NativeOpacity {
    private val values = ConcurrentHashMap<String, Int>()

    fun of(identity: String): Int =
        values.computeIfAbsent(identity) {
            try {
                val data = Bukkit.createBlockData(identity)
                if (data.material.name.endsWith("_LEAVES")) 15
                else {
                    val state = data.javaClass.getMethod("getState").invoke(data)
                    val method =
                        state.javaClass.methods.firstOrNull { method ->
                            method.parameterCount == 0 &&
                                method.name in setOf("getLightDampening", "getLightBlock")
                        }
                    requireNotNull(method) { "Paper state opacity API unavailable" }
                    (method.invoke(state) as Int).coerceIn(0, 15)
                }
            } catch (_: IllegalArgumentException) {
                0
            }
        }
}
