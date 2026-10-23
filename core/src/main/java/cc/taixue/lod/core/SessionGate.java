package cc.taixue.lod.core;

import java.util.*;

/** Main-thread-owned leases: changing world, permission or connection retires every old job. */
public final class SessionGate {
    public record Lease(UUID player, UUID world, long epoch) {}
    private record State(UUID world, long epoch, boolean allowed) {}
    private final Map<UUID,State> states=new HashMap<>();
    private long nextEpoch;
    public Lease observe(UUID player, UUID world, boolean allowed) {
        var old=states.get(player);
        if(old==null || !old.world.equals(world) || old.allowed!=allowed) {
            old=new State(world,++nextEpoch,allowed);states.put(player,old);
        }
        return allowed?new Lease(player,world,old.epoch):null;
    }
    public boolean valid(Lease lease) {
        if(lease==null)return false;
        var s=states.get(lease.player);
        return s!=null && s.allowed && s.epoch==lease.epoch && s.world.equals(lease.world);
    }
    public void remove(UUID player) { states.remove(player); }
    public void clear() { states.clear(); }
}
