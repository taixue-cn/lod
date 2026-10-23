package cc.taixue.lod.fixture

import cc.taixue.lod.server.LodServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.java.JavaPlugin

/** Test-only loopback worlds and permissions; never included in the released plugin. */
class Fixture : JavaPlugin(), Listener {
    private lateinit var publicWorld: World
    private lateinit var secret: World
    private val attachments = mutableMapOf<UUID, PermissionAttachment>()

    override fun onEnable() {
        check(server.ip == "127.0.0.1") { "Fixture requires loopback binding" }
        publicWorld = Bukkit.getWorlds().first()
        secret =
            WorldCreator("lod-secret")
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings(
                    """{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":4}],"features":false}"""
                )
                .createWorld()!!
        for (world in listOf(publicWorld, secret)) {
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            world.setGameRule(GameRule.SPAWN_CHUNK_RADIUS, 0)
            world.setSpawnLocation(0, 64, 0)
            for (x in 4 until 8) for (z in 4 until 8) world.getChunkAt(x, z)
            world.getBlockAt(64, 64, 64).type =
                if (world == secret) Material.DIAMOND_BLOCK else Material.GOLD_BLOCK
        }
        server.pluginManager.registerEvents(this, this)
        getCommand("lodfixture")!!.setExecutor { sender, _, _, args ->
            if (sender !is Player || args.size != 1) return@setExecutor false
            val attachment = attachments[sender.uniqueId] ?: return@setExecutor false
            when (args[0]) {
                "cachetest" -> CacheFixture(this, lod()).start()
                "revoke" ->
                    attachment.setPermission("multiverse.access.${sender.world.name}", false)
                "grant" -> attachment.setPermission("multiverse.access.${sender.world.name}", true)
                "secret" -> sender.teleport(Location(secret, 0.5, 64.0, 0.5))
                "public" -> sender.teleport(Location(publicWorld, 0.5, 64.0, 0.5))
                "grantsecret" -> attachment.setPermission("multiverse.access.${secret.name}", true)
                "clear" -> lod().voxyService.dirty(publicWorld, 4, 4)
                "stats" -> {
                    val uploads = lod().voxyService.uploads()
                    Files.writeString(
                        Path.of("upload-stats.json"),
                        """{"accepted":${uploads.accepted()},"rejected":${uploads.rejected()}}""",
                    )
                }
                "globaldeny" -> policy("whitelist", listOf("lod-secret"))
                "globalblacklist" -> policy("blacklist", listOf("world"))
                "globalallow" -> policy("whitelist", listOf("world"))
                "edit" ->
                    Class.forName("cc.taixue.lod.fixture.WorldEditFixture")
                        .getMethod("edit", String::class.java)
                        .invoke(null, publicWorld.name)
                "mutate" -> publicWorld.getBlockAt(64, 64, 64).type = Material.EMERALD_BLOCK
                else -> return@setExecutor false
            }
            sender.sendMessage("FIXTURE:${args[0]}")
            true
        }
        logger.info("LOD_FIXTURE_READY")
    }

    private fun lod() = Bukkit.getPluginManager().getPlugin("LodServer") as LodServer

    private fun policy(mode: String, entries: List<String>) {
        lod().config.set("lod.worlds.mode", mode)
        lod().config.set("lod.worlds.entries", entries)
        lod().worldAccess.refreshPolicy()
    }

    @EventHandler
    fun join(event: PlayerJoinEvent) {
        val player = event.player
        // Paper sends a margin beyond view-distance=2; keep the native probe outside that margin.
        if (player.name == "LODNative") player.teleport(Location(publicWorld, -63.5, 64.0, -63.5))
        val attachment = player.addAttachment(this)
        attachments[player.uniqueId] = attachment
        attachment.setPermission("archlod.use", true)
        attachment.setPermission("archlod.upload", true)
        attachment.setPermission("multiverse.access.${publicWorld.name}", player.name != "LODNo")
        attachment.setPermission("multiverse.access.${secret.name}", false)
    }
}
