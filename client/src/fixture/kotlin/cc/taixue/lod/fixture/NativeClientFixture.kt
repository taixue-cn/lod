package cc.taixue.lod.fixture

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mojang.brigadier.CommandDispatcher
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress

/** Isolated client fixture, never included in the player jar. */
class NativeClientFixture : ClientModInitializer {
    private var started = 0L
    private var ticks = 0
    private var done = false
    private var connecting = false
    private var commandChecked = false
    @Volatile private var dhReading = false
    @Volatile private var dhGold = false
    @Volatile private var dhError = ""
    private var dhCache: Any? = null

    override fun onInitializeClient() {
        started = System.currentTimeMillis()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (done || ++ticks % 20 != 0) return@register
            try {
                val level = client.level
                if (level == null) {
                    if (!connecting && ticks >= 100) {
                        connecting = true
                        val address = System.getenv("ARCH_LOD_NATIVE_SERVER")
                        ConnectScreen.startConnecting(
                            TitleScreen(),
                            client,
                            ServerAddress.parseString(address),
                            ServerData("isolated", address, ServerData.Type.OTHER),
                            false,
                            null,
                        )
                    }
                    if (System.currentTimeMillis() - started > 120000)
                        finish(client, false, "no multiplayer world")
                    return@register
                }
                if (
                    System.getenv("ARCH_LOD_OMIT_BRIDGE") != "1" &&
                        !commandChecked &&
                        !checkClientCommand(client)
                )
                    return@register
                when (System.getProperty("archlod.fixture.mode", "voxy")) {
                    "fallback" ->
                        if (ticks > 400) {
                            finish(
                                client,
                                true,
                                "normal multiplayer world with no server handshake",
                            )
                            return@register
                        }
                    "voxy" -> {
                        bridgeDebug()
                        // The server sends radius 2; target (4,4) is inside the client's configured
                        // radius 12.
                        client.options.renderDistance().set(12)
                        val read =
                            Class.forName("cc.taixue.lod.client.VoxyCacheReader")
                                .getDeclaredMethod(
                                    "read",
                                    ClientLevel::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                )
                                .apply { isAccessible = true }
                        val body = read.invoke(null, level, 4, 4) as ByteArray?
                        Files.writeString(
                            Path.of(System.getProperty("archlod.fixture.report") + ".terrain"),
                            "bytes=${body?.size}, gold=${body?.toString(Charsets.ISO_8859_1)?.contains("minecraft:gold_block")}, vanilla=${level.chunkSource.hasChunk(4, 4)}, accepted=${acceptedColumn()}",
                        )
                        if (
                            body != null &&
                                body
                                    .toString(Charsets.ISO_8859_1)
                                    .contains("minecraft:gold_block") &&
                                !level.chunkSource.hasChunk(4, 4) &&
                                acceptedColumn()
                        ) {
                            val entry = entries().single()
                            for (name in listOf("receivedColumns", "queuedColumns")) {
                                val field =
                                    entry.javaClass.getDeclaredField(name).apply {
                                        isAccessible = true
                                    }
                                check(field.getLong(entry) > 0)
                            }
                            check(checkClientCommand(client))
                            finish(
                                client,
                                true,
                                "unmodified Voxy engine contains remote gold block outside vanilla loaded chunks",
                            )
                            return@register
                        }
                    }
                    "dh" -> {
                        dhDebug()
                        if (dhGold && !level.chunkSource.hasChunk(4, 4)) {
                            finish(
                                client,
                                true,
                                "unmodified DH terrain API contains remote gold block outside vanilla loaded chunks",
                            )
                            return@register
                        }
                        if (!dhReading) {
                            dhReading = true
                            Thread(
                                    {
                                        try {
                                            dhGold = dhGold()
                                        } catch (error: Throwable) {
                                            dhError = "$error ${error.cause}"
                                        } finally {
                                            dhReading = false
                                        }
                                    },
                                    "DH-fixture-query",
                                )
                                .apply {
                                    isDaemon = true
                                    start()
                                }
                        }
                    }
                }
                if (System.currentTimeMillis() - started > 120000)
                    finish(client, false, "remote terrain not observed: $dhError")
            } catch (error: Throwable) {
                finish(client, false, error.toString())
            }
        }
    }

    /**
     * Execute the real client command dispatcher, including the handler's saved preference change.
     */
    private fun checkClientCommand(client: Minecraft): Boolean {
        val manager =
            try {
                Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommandManager")
            } catch (_: ClassNotFoundException) {
                Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommands")
            }
        @Suppress("UNCHECKED_CAST")
        val dispatcher =
            manager.getMethod("getActiveDispatcher").invoke(null)
                as CommandDispatcher<FabricClientCommandSource>? ?: return false
        val source = client.connection!!.suggestionsProvider as FabricClientCommandSource
        check(dispatcher.execute("lodc status", source) == 1)
        check(dispatcher.execute("lodc radius 48", source) == 1)
        val entry = entries().single()
        val preferences =
            entry.javaClass.getDeclaredField("preferences").apply { isAccessible = true }.get(entry)
        val radius = preferences.javaClass.getDeclaredMethod("radius").apply { isAccessible = true }
        check(radius.invoke(preferences) == 48)
        check(dispatcher.execute("lodc radius 64", source) == 1)
        commandChecked = true
        return true
    }

    private fun entries(): List<Any> =
        FabricLoader.getInstance()
            .getEntrypointContainers("client", ClientModInitializer::class.java)
            .map { it.entrypoint }
            .filter { it.javaClass.name == "cc.taixue.lod.client.LodClient" }

    private fun bridgeDebug() {
        val lines = mutableListOf<String>()
        for (entry in entries()) for (name in
            listOf("enabled", "handshook", "ticks", "cursor", "errors", "pending", "timestamps")) {
            val value =
                entry.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(entry)
            lines +=
                "$name: ${if (value is Map<*, *>) "size=${value.size}, target=${value[(4L shl 32) or 4L]}" else value}"
        }
        Files.write(Path.of(System.getProperty("archlod.fixture.report") + ".debug"), lines)
    }

    private fun acceptedColumn(): Boolean =
        entries().any { entry ->
            val value =
                entry.javaClass
                    .getDeclaredField("timestamps")
                    .apply { isAccessible = true }
                    .get(entry) as Map<*, *>
            value.containsKey((4L shl 32) or 4L)
        }

    private fun dhDebug() {
        try {
            val world =
                Class.forName("com.seibel.distanthorizons.core.api.internal.SharedApi")
                    .getMethod("getAbstractDhWorld")
                    .invoke(null)
            val lines = mutableListOf<String>()
            if (world != null) {
                world.javaClass
                    .getMethod("addDebugMenuStringsToList", List::class.java)
                    .invoke(world, lines)
                val state = world.javaClass.getField("networkState").get(world)
                lines += "session: ${state.javaClass.getField("sessionConfig").get(state)}"
                for (level in
                    world.javaClass.getMethod("getAllLoadedLevels").invoke(world)
                        as Iterable<*>) level!!
                    .javaClass
                    .getMethod("addDebugMenuStringsToList", List::class.java)
                    .invoke(level, lines)
            }
            lines += "API error: $dhError"
            Files.write(Path.of(System.getProperty("archlod.fixture.report") + ".debug"), lines)
        } catch (_: Exception) {
            // Diagnostics must not replace the actual terrain assertion.
        }
    }

    private fun dhGold(): Boolean {
        val delayed = Class.forName("com.seibel.distanthorizons.api.DhApi\$Delayed")
        val proxy = delayed.getField("worldProxy").get(null) ?: return false
        val repository = delayed.getField("terrainRepo").get(null) ?: return false
        val worldApi =
            Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy")
        val levelApi =
            Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper")
        val cacheApi =
            Class.forName("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache")
        val repoApi =
            Class.forName("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo")
        dhCache = repoApi.getMethod("createSoftCache").invoke(repository)
        for (level in
            worldApi.getMethod("getAllLoadedLevelWrappers").invoke(proxy) as Iterable<*>) {
            val result =
                repoApi
                    .getMethod(
                        "getSingleDataPointAtBlockPos",
                        levelApi,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        cacheApi,
                    )
                    .invoke(repository, level, 64, 64, 64, dhCache)
            if (!result.javaClass.getField("success").getBoolean(result)) {
                dhError = result.javaClass.getField("message").get(result).toString()
                continue
            }
            val point = result.javaClass.getField("payload").get(result) ?: continue
            val block = point.javaClass.getField("blockStateWrapper").get(point)
            val blockApi =
                Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper"
                )
            if (
                blockApi
                    .getMethod("getSerialString")
                    .invoke(block)
                    .toString()
                    .contains("gold_block")
            )
                return true
        }
        return false
    }

    private fun finish(client: Minecraft, pass: Boolean, detail: String) {
        done = true
        val path = Path.of(System.getProperty("archlod.fixture.report"))
        Files.createDirectories(path.parent)
        val json =
            JsonObject().apply {
                addProperty("status", if (pass) "PASS" else "FAIL")
                addProperty("detail", detail)
                addProperty("ticks", ticks)
                addProperty("production_server", false)
                addProperty(
                    "client_command",
                    if (commandChecked) "lodc radius verified" else "not verified",
                )
            }
        Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(json))
        client.stop()
    }
}
