package cc.taixue.lod.server;

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.biome.BiomeType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import no.jckf.dhsupport.core.DhSupport;
import java.util.*;

/** Coalesces asynchronous WorldEdit/FAWE changes before touching either terrain cache. */
public final class WorldEditInvalidation implements AutoCloseable {
    private record Key(String world,int x,int z){}
    private final Map<Key,Long> pending=new LinkedHashMap<>();
    private final JavaPlugin plugin;
    private final VoxyService voxy;
    private final DhSupport dh;
    private final org.bukkit.scheduler.BukkitTask task;
    public WorldEditInvalidation(JavaPlugin plugin,VoxyService voxy,DhSupport dh){
        this.plugin=plugin;this.voxy=voxy;this.dh=dh;
        WorldEdit.getInstance().getEventBus().register(this);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::flush,20,20);
    }
    @Subscribe public void edit(EditSessionEvent event){
        if(event.getStage()!=EditSession.Stage.BEFORE_CHANGE||event.getWorld()==null)return;
        String name=event.getWorld().getName();
        event.setExtent(new AbstractDelegateExtent(event.getExtent()){
            @Override public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 p,T block)throws WorldEditException{
                boolean changed=super.setBlock(p,block);if(changed)mark(name,p);return changed;
            }
            @Override public boolean setBiome(BlockVector3 p,BiomeType biome){boolean changed=super.setBiome(p,biome);if(changed)mark(name,p);return changed;}
        });
    }
    private synchronized void mark(String world,BlockVector3 p){pending.put(new Key(world,p.x()>>4,p.z()>>4),System.currentTimeMillis());}
    private void flush(){
        var ready=new ArrayList<Key>();long now=System.currentTimeMillis();
        synchronized(this){var it=pending.entrySet().iterator();while(it.hasNext()&&ready.size()<128){var e=it.next();if(now-e.getValue()>=500){ready.add(e.getKey());it.remove();}}}
        for(var k:ready){var world=Bukkit.getWorld(k.world);if(world!=null){voxy.dirty(world,k.x,k.z);dh.touchLod(world.getUID(),k.x<<4,k.z<<4,"WorldEdit");}}
    }
    public void close(){task.cancel();WorldEdit.getInstance().getEventBus().unregister(this);synchronized(this){pending.clear();}}
}
