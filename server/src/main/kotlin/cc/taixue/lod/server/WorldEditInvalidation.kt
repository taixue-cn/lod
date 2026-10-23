package cc.taixue.lod.server

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.eventbus.Subscribe
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.block.BlockStateHolder
import no.jckf.dhsupport.core.DhSupport
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/** Coalesces asynchronous WorldEdit/FAWE changes before accessing either terrain cache. */
class WorldEditInvalidation(
    plugin: JavaPlugin,
    private val voxy: VoxyService,
    private val dh: DhSupport,
) : AutoCloseable {
    private data class Key(val world: String, val x: Int, val z: Int)

    private val pending = linkedMapOf<Key, Long>()
    private val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::flush), 20, 20)

    init {
        WorldEdit.getInstance().eventBus.register(this)
    }

    @Subscribe
    fun edit(event: EditSessionEvent) {
        if (event.stage != EditSession.Stage.BEFORE_CHANGE) return
        val name = event.world?.name ?: return
        event.extent = TrackingExtent(name, event.extent)
    }

    /** A stable JVM class name is required by FAWE's configured extent allowlist. */
    private inner class TrackingExtent(private val world: String, delegate: Extent) :
        AbstractDelegateExtent(delegate) {
        @Throws(WorldEditException::class)
        override fun <T : BlockStateHolder<T>> setBlock(position: BlockVector3, block: T): Boolean {
            val changed = super.setBlock(position, block)
            if (changed) mark(world, position)
            return changed
        }

        override fun setBiome(position: BlockVector3, biome: BiomeType): Boolean {
            val changed = super.setBiome(position, biome)
            if (changed) mark(world, position)
            return changed
        }
    }

    @Synchronized
    private fun mark(world: String, position: BlockVector3) {
        pending[Key(world, position.x() shr 4, position.z() shr 4)] = System.currentTimeMillis()
    }

    private fun flush() {
        val ready = mutableListOf<Key>()
        val now = System.currentTimeMillis()
        synchronized(this) {
            val iterator = pending.iterator()
            while (iterator.hasNext() && ready.size < 128) {
                val entry = iterator.next()
                if (now - entry.value >= 500) {
                    ready += entry.key
                    iterator.remove()
                }
            }
        }
        for (key in ready) {
            val world = Bukkit.getWorld(key.world) ?: continue
            voxy.dirty(world, key.x, key.z)
            dh.touchLod(world.uid, key.x shl 4, key.z shl 4, "WorldEdit")
        }
    }

    override fun close() {
        task.cancel()
        WorldEdit.getInstance().eventBus.unregister(this)
        synchronized(this) { pending.clear() }
    }
}
