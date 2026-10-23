package cc.taixue.lod.server;
import cc.taixue.lod.core.SessionGate;
import no.jckf.dhsupport.core.handler.PluginMessageHandler;
import no.jckf.dhsupport.core.message.plugin.PluginMessage;
import java.util.UUID;

/** Native DH and Voxy use the same world-access leases. */
public final class DhGuard {
    private static WorldAccess access;
    private static PluginMessageHandler messages;
    public static void install(WorldAccess a,PluginMessageHandler m){access=a;messages=m;}
    public static SessionGate.Lease issue(UUID uuid,UUID world){return access==null?null:access.issue(uuid,world);}
    public static boolean cacheAllowed(UUID world){return access!=null&&access.cacheAllowed(world);}
    public static boolean inRange(SessionGate.Lease l,long x,long z){return access!=null && access.inRange(l,x,z);}
    public static void send(SessionGate.Lease l, PluginMessage message){
        var a=access;var m=messages;
        if(l!=null && a!=null && m!=null)a.send(l,()->m.sendPluginMessage(l.player(),message));
    }
    private static final java.util.Map<UUID,String> lastState=new java.util.HashMap<>();
    public static void watch(no.jckf.dhsupport.core.DhSupport service) {
        if(access==null)return;
        lastState.keySet().removeIf(id->org.bukkit.Bukkit.getPlayer(id)==null);
        for(UUID id:new java.util.ArrayList<>(service.getPlayerConfigurations().keySet())) {
            var p=org.bukkit.Bukkit.getPlayer(id);if(p==null)continue;
            var world=p.getWorld().getUID();var cfg=service.getPlayerConfiguration(id,world);if(cfg==null)continue;
            var lease=access.issue(id,world);String state=world+":"+(lease==null?"denied":lease.epoch());
            if(state.equals(lastState.put(id,state)))continue;
            var reply=new no.jckf.dhsupport.core.message.plugin.RemotePlayerConfigMessage();reply.fromConfiguration(cfg);
            if(lease==null){reply.setDistantGenerationEnabled(false);reply.setRealTimeUpdatesEnabled(false);messages.sendPluginMessage(id,reply);}
            else send(lease,reply);
        }
    }
    public static void clear(){access=null;messages=null;lastState.clear();}
}
