package cc.taixue.lod.server

import cc.taixue.lod.core.ManagedWorldPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.bukkit.Bukkit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls world policies from the trusted management service; all Bukkit mutations stay on its
 * thread.
 */
class ManagedWorlds(private val plugin: LodServer) : AutoCloseable {
    private val policy = ManagedWorldPolicy()
    private val endpoint = URI.create(plugin.config.getString("management.sync-url", "")!!)
    private val token = plugin.config.getString("management.token", "")!!
    private val serverId = plugin.config.getInt("management.server-id", 0)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LOD-management-sync").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var pending = false
    private var warned = false
    private var connected = false

    init {
        require(endpoint.scheme in setOf("https", "http") && endpoint.host != null)
        require(endpoint.userInfo == null && endpoint.fragment == null && endpoint.query == null)
        require(token.length >= 32 && token.none { it == '\r' || it == '\n' } && serverId > 0) {
            "management requires a server ID and a strong token"
        }
    }

    private val timer = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::sync), 1, 200)

    fun allows(world: UUID): Boolean =
        !closed && policy.allows(world) && !plugin.cacheManager.blocked(world)

    fun revision(world: UUID): Long = policy.entry(world)?.revision ?: 0L

    private fun sync() {
        if (closed || pending) return
        val worlds = (Bukkit.getWorlds().map { it.uid } + plugin.cacheManager.knownWorlds()).toSet()
        // This bound protects both the worker and the management service; unreported worlds deny.
        if (worlds.size > 2048) {
            policy.clear()
            plugin.logger.warning("LOD management refuses more than 2048 loaded worlds")
            return
        }
        val expected = worlds
        val reports = JSONArray()
        worlds.forEach { world ->
            val entry = policy.entry(world)
            if (!allows(world)) plugin.warmService.stop(world)
            val status =
                if (entry?.enabled == false || plugin.cacheManager.blocked(world))
                    plugin.cacheManager.deletionStatus(world)
                else plugin.warmService.status(world)
            reports.put(
                JSONObject()
                    .put("world_uuid", world.toString())
                    .put("revision", entry?.revision ?: 0)
                    .put("state", status.state)
                    .put("completed_chunks", status.completedChunks)
                    .put("failed_chunks", status.failedChunks)
            )
        }
        val requestStarted = System.nanoTime()
        val body = JSONObject().put("server_id", serverId).put("worlds", reports).toString()
        val request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        pending = true
        worker.execute {
            try {
                val transfer =
                    client.sendAsync(
                        request,
                        HttpResponse.BodyHandler { ManagementBodySubscriber(2 * 1024 * 1024) },
                    )
                val response =
                    try {
                        transfer.get(6, TimeUnit.SECONDS)
                    } catch (error: Exception) {
                        transfer.cancel(true)
                        throw error
                    }
                val bytes = response.body()
                check(response.statusCode() == 200) {
                    "management response rejected (${response.statusCode()})"
                }
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                check(json.optJSONObject("base")?.optString("status", "OK") in listOf(null, "OK"))
                val values = json.getJSONArray("worlds")
                check(values.length() == expected.size) { "incomplete world policies" }
                val entries = mutableMapOf<UUID, ManagedWorldPolicy.Entry>()
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    val uuid = UUID.fromString(value.getString("world_uuid"))
                    check(uuid in expected && uuid !in entries) { "unexpected world policy" }
                    entries[uuid] =
                        ManagedWorldPolicy.Entry(
                            value.getLong("revision"),
                            value.getBoolean("enabled"),
                            value.optLong("purge_revision", 0L),
                        )
                }
                check(
                    entries.values.all {
                        it.revision >= 0 &&
                            it.purgeRevision in 0..it.revision &&
                            (!it.enabled || it.revision > 0)
                    }
                )
                main {
                    val previous = expected.associateWith(::allows)
                    val revisions = expected.associateWith { policy.entry(it)?.revision }
                    policy.replace(entries, requestStarted)
                    if (!connected || warned) {
                        plugin.logger.info(
                            "LOD management connected: ${entries.values.count { it.enabled }} / ${entries.size} loaded worlds enabled"
                        )
                        connected = true
                    }
                    expected.forEach { uuid ->
                        val world = Bukkit.getWorld(uuid)
                        if (
                            revisions[uuid] != entries[uuid]?.revision &&
                                (previous[uuid] == true || entries[uuid]?.enabled == true)
                        ) {
                            plugin.logger.info(
                                "LOD policy applied world=$uuid enabled=${allows(uuid)} revision=${entries[uuid]?.revision}"
                            )
                        }
                        val entry = entries[uuid] ?: return@forEach
                        if (!entry.enabled) {
                            plugin.warmService.stop(uuid)
                            // Unknown/unbound revisions deny access but never authorize
                            // destruction.
                            if (entry.revision > 0)
                                plugin.cacheManager.disableWorld(
                                    uuid,
                                    maxOf(entry.purgeRevision, entry.revision),
                                )
                        } else if (
                            plugin.cacheManager.enableWorld(uuid, entry.purgeRevision) &&
                                world != null &&
                                (previous[uuid] != true ||
                                    revisions[uuid] != entry.revision ||
                                    plugin.warmService.status(uuid).state == "OFF")
                        ) {
                            plugin.warmService.stop(uuid)
                            plugin.warmService.start(world, setOf("voxy", "dh"))
                        }
                    }
                    pending = false
                    warned = false
                }
            } catch (_: Exception) {
                // Never log response bodies or credentials; the existing lease expires within 30s.
                main {
                    pending = false
                    if (!warned)
                        plugin.logger.warning(
                            "LOD management sync unavailable; terrain leases will expire"
                        )
                    warned = true
                }
            }
        }
    }

    private fun main(operation: () -> Unit) {
        if (!closed && plugin.isEnabled) {
            Bukkit.getScheduler().runTask(plugin, Runnable { if (!closed) operation() })
        }
    }

    override fun close() {
        closed = true
        timer.cancel()
        policy.clear()
        worker.shutdownNow()
        client.shutdownNow()
        Bukkit.getWorlds().forEach { plugin.warmService.stop(it.uid) }
    }
}
