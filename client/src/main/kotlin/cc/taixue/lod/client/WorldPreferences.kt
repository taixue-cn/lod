package cc.taixue.lod.client

import cc.taixue.lod.core.Wire
import cc.taixue.lod.core.WorldPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Per-player, per-server choices; the server's world policy and permissions always take precedence.
 */
internal class WorldPreferences(private val diagnostics: () -> List<String>) {
    private val path = FabricLoader.getInstance().configDir.resolve("taixue-lod-worlds.json")
    private var profiles = JsonObject()

    init {
        try {
            if (Files.exists(path))
                profiles = JsonParser.parseString(Files.readString(path)).asJsonObject
        } catch (_: Exception) {
            // Malformed local preferences fall back to the default download policy.
        }
        registerCommands()
    }

    private fun address(): String =
        Minecraft.getInstance().currentServer?.ip?.lowercase(Locale.ROOT) ?: "singleplayer"

    private fun current(): JsonObject {
        val key = address()
        if (!profiles.has(key))
            profiles.add(
                key,
                JsonObject().apply {
                    addProperty("mode", "blacklist")
                    add("entries", JsonArray())
                },
            )
        return profiles.getAsJsonObject(key)
    }

    private fun entries(): List<String> = current().getAsJsonArray("entries").map { it.asString }

    fun allows(): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val id = level.dimension().identifier()
        return try {
            WorldPolicy(current().get("mode").asString, entries()).allows(id.toString(), id.path)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun radius(): Int =
        (if (current().has("radiusChunks")) current().get("radiusChunks").asInt else 64).coerceIn(
            8,
            128,
        )

    fun frame(): ByteArray {
        val output = Wire.Writer().utf(current().get("mode").asString)
        val entries = entries()
        output.varInt(entries.size)
        entries.forEach(output::utf)
        return output.result()
    }

    private fun save() {
        try {
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(profiles))
        } catch (error: IOException) {
            throw IllegalStateException("Cannot save LOD preferences", error)
        }
    }

    private fun say(message: String) {
        val player = Minecraft.getInstance().player ?: return
        player.displayClientMessage(Component.literal(message), false)
    }

    private fun mode(value: String): Int {
        current().addProperty("mode", value)
        save()
        say("LOD downloads: $value ${entries()}")
        return 1
    }

    private fun edit(add: Boolean, entry: String): Int {
        val value =
            if (entry == "current")
                (Minecraft.getInstance().level ?: return 0).dimension().identifier().toString()
            else entry
        val entries = entries().toMutableList()
        if (add) {
            if (entries.size >= 64) {
                say("At most 64 world entries")
                return 0
            }
            if (value !in entries) entries += value
        } else entries.remove(value)
        current().add("entries", JsonArray().apply { entries.forEach(::add) })
        save()
        say("LOD downloads: ${current().get("mode").asString} $entries")
        return 1
    }

    private fun registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("lodc")
                    .then(
                        literal("status").executes {
                            diagnostics().forEach(::say)
                            1
                        }
                    )
                    .then(
                        literal("radius")
                            .executes {
                                say("LOD download radius: ${radius()} chunks")
                                1
                            }
                            .then(
                                argument("chunks", IntegerArgumentType.integer(8, 128)).executes {
                                    current()
                                        .addProperty(
                                            "radiusChunks",
                                            IntegerArgumentType.getInteger(it, "chunks"),
                                        )
                                    save()
                                    say("LOD download radius: ${radius()} chunks")
                                    1
                                }
                            )
                    )
                    .then(
                        literal("worlds")
                            .executes {
                                say(current().toString())
                                1
                            }
                            .then(
                                literal("mode")
                                    .then(literal("whitelist").executes { mode("whitelist") })
                                    .then(literal("blacklist").executes { mode("blacklist") })
                            )
                            .then(
                                literal("add")
                                    .then(
                                        argument("world", StringArgumentType.greedyString())
                                            .executes {
                                                edit(
                                                    true,
                                                    StringArgumentType.getString(it, "world"),
                                                )
                                            }
                                    )
                            )
                            .then(
                                literal("remove")
                                    .then(
                                        argument("world", StringArgumentType.greedyString())
                                            .executes {
                                                edit(
                                                    false,
                                                    StringArgumentType.getString(it, "world"),
                                                )
                                            }
                                    )
                            )
                    )
            )
        }
    }
}
