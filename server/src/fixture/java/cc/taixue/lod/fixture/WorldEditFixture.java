package cc.taixue.lod.fixture;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
public final class WorldEditFixture {
    public static void edit(String world)throws Exception{
        var we=WorldEdit.getInstance();var w=we.getPlatformManager().queryCapability(Capability.WORLD_EDITING).getWorlds().stream().filter(x->x.getName().equals(world)).findFirst().orElseThrow();
        try(var edit=we.newEditSession(w)){edit.setBlock(BlockVector3.at(64,64,64),BlockTypes.EMERALD_BLOCK.getDefaultState());}
    }
}
