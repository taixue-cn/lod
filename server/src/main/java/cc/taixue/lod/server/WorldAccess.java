package cc.taixue.lod.server;

import cc.taixue.lod.core.SessionGate;
import cc.taixue.lod.core.WorldPolicy;
import org.bukkit.event.world.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Main-thread-owned authorization. Unresolved world permissions always deny. */
public final class WorldAccess implements Listener {
    private final JavaPlugin plugin;
    private final SessionGate gate=new SessionGate();
    private volatile WorldPolicy policy;
    private final Map<UUID,WorldPolicy> personal=new HashMap<>();
    public void preference(UUID player,WorldPolicy preference){personal.put(player,preference);retire(player);}
    private final java.util.concurrent.ConcurrentMap<UUID,String[]> knownWorlds=new java.util.concurrent.ConcurrentHashMap<>();
    public void refreshPolicy(){policy=new WorldPolicy(plugin.getConfig().getString("lod.worlds.mode","blacklist"),plugin.getConfig().getStringList("lod.worlds.entries"));}
    public boolean cacheAllowed(UUID world){var names=knownWorlds.get(world);return names!=null&&policy.allows(names);}
    private void know(World w){knownWorlds.put(w.getUID(),new String[]{w.getName(),w.getKey().toString(),w.getUID().toString()});}
    @EventHandler public void loadWorld(WorldLoadEvent e){know(e.getWorld());}
    @EventHandler public void unloadWorld(WorldUnloadEvent e){knownWorlds.remove(e.getWorld().getUID());}
    public WorldAccess(JavaPlugin plugin) {
        this.plugin=plugin;refreshPolicy();Bukkit.getWorlds().forEach(this::know);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,()-> {
            for(Player p:plugin.getServer().getOnlinePlayers()) observe(p);
        },1,1);
    }
    private boolean allowed(Player p) {
        try {
            String node=plugin.getConfig().getString("lod.access-permission","multiverse.access.%s").formatted(p.getWorld().getName());
            return cacheAllowed(p.getWorld().getUID()) && (!personal.containsKey(p.getUniqueId())||personal.get(p.getUniqueId()).allows(knownWorlds.get(p.getWorld().getUID()))) && plugin.isEnabled() && p.isOnline() && p.hasPermission("archlod.use") && p.hasPermission(node);
        } catch(RuntimeException error) { return false; }
    }
    private SessionGate.Lease observe(Player p) {return gate.observe(p.getUniqueId(),p.getWorld().getUID(),allowed(p));}
    public SessionGate.Lease issue(UUID uuid, UUID world) {
        requireMain();Player p=Bukkit.getPlayer(uuid);
        if(p==null || !p.getWorld().getUID().equals(world))return null;
        return observe(p);
    }
    public boolean valid(SessionGate.Lease lease) {
        requireMain();if(lease==null)return false;
        Player p=Bukkit.getPlayer(lease.player());
        if(p==null || !p.isOnline()) {gate.remove(lease.player());return false;}
        observe(p);return gate.valid(lease);
    }
    public boolean inRange(SessionGate.Lease lease, long cx, long cz) {
        if(!valid(lease) || Math.abs(cx)>1_875_000 || Math.abs(cz)>1_875_000)return false;
        Player p=Bukkit.getPlayer(lease.player());
        int radius=Math.clamp(plugin.getConfig().getInt("lod.radius-chunks",128),8,512);
        long px=p.getLocation().getBlockX()>>4,pz=p.getLocation().getBlockZ()>>4;
        return Math.abs(px-cx)<=radius && Math.abs(pz-cz)<=radius;
    }
    public void send(SessionGate.Lease lease, Runnable operation) {
        if(!plugin.isEnabled())return;
        Runnable guarded=()->{if(valid(lease))operation.run();};
        if(Bukkit.isPrimaryThread())guarded.run();else Bukkit.getScheduler().runTask(plugin,guarded);
    }
    public void retire(UUID uuid) { gate.remove(uuid); }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){retire(e.getPlayer().getUniqueId());personal.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void changed(PlayerChangedWorldEvent e){retire(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){retire(e.getPlayer().getUniqueId());personal.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e){retire(e.getPlayer().getUniqueId());}
    public void close(){personal.clear();gate.clear();HandlerList.unregisterAll(this);}
    private static void requireMain(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("LOD authorization off main thread");}
}
