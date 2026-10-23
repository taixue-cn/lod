package cc.taixue.lod.fixture

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.block.BlockTypes

object WorldEditFixture {
    @JvmStatic
    fun edit(name: String) {
        val worldEdit = WorldEdit.getInstance()
        val world =
            worldEdit.platformManager.queryCapability(Capability.WORLD_EDITING).worlds.first {
                it.name == name
            }
        worldEdit.newEditSession(world).use {
            it.setBlock(BlockVector3.at(64, 64, 64), BlockTypes.EMERALD_BLOCK!!.defaultState)
        }
    }
}
