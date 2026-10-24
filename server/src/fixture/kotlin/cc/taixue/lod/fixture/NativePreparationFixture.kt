package cc.taixue.lod.fixture

import cc.taixue.lod.core.ColumnCache
import cc.taixue.lod.core.NativeRegionStore
import cc.taixue.lod.core.PreparedColumn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * Optional isolated preparation/benchmark from an immutable cache snapshot; never loads its worlds.
 */
object NativePreparationFixture {
    fun start(plugin: JavaPlugin) {
        val source = System.getProperty("lod.fixture.native-source") ?: return
        val output = Path.of(System.getProperty("lod.fixture.native-output"))
        val world = UUID.fromString(System.getProperty("lod.fixture.native-world"))
        val opacityClass = Class.forName("cc.taixue.lod.server.NativeOpacity")
        val provider = opacityClass.getDeclaredConstructor().newInstance()
        val method = opacityClass.getMethod("of", String::class.java)
        check(method.invoke(provider, "minecraft:stone") == 15)
        Thread(
                {
                    try {
                        val cache = ColumnCache(Path.of(source), Duration.ofSeconds(-1), -1)
                        val native = NativeRegionStore(output)
                        val entries =
                            cache
                                .catalog()
                                .filter { it.first.world == world }
                                .take(
                                    System.getProperty("lod.fixture.native-limit", "1000000")
                                        .toInt()
                                )
                        val started = System.nanoTime()
                        var compressed = 0L
                        var original = 0L
                        var count = 0
                        for ((key, version) in entries) {
                            val entry = cache.get(key) ?: continue
                            val body =
                                PreparedColumn.prepare(entry.body) {
                                    method.invoke(provider, it) as Int
                                }
                            native.put(key, version, body)
                            compressed += body.size
                            original += entry.body.size
                            count++
                            if (count % 1000 == 0)
                                plugin.logger.info("NATIVE_PREPARE $count / ${entries.size}")
                        }
                        val millis = (System.nanoTime() - started) / 1000000
                        Files.writeString(
                            Path.of("native-preparation.json"),
                            """{"status":"PASS","columns":$count,"elapsed_ms":$millis,"compressed_bytes":$compressed,"original_bytes":$original}""",
                        )
                        plugin.logger.info("NATIVE_PREPARE_DONE count=$count elapsed_ms=$millis")
                    } catch (error: Throwable) {
                        Files.writeString(
                            Path.of("native-preparation.json"),
                            """{"status":"FAIL","error":"${error.javaClass.simpleName}"}""",
                        )
                        plugin.logger.warning("NATIVE_PREPARE_FAILED $error")
                    } finally {
                        Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.shutdown() })
                    }
                },
                "LOD-native-preparation-fixture",
            )
            .apply {
                isDaemon = true
                start()
            }
    }
}
