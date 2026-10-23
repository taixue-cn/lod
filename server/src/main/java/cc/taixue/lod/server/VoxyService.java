package cc.taixue.lod.server;

import cc.taixue.lod.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** LSS v20 Paper adapter: bounded work, persistent shared terrain, per-send authorization. */
public final class VoxyService implements PluginMessageListener,Listener,AutoCloseable {
    private static final String HANDSHAKE="lss:handshake_c2s",REQUEST="lss:batch_chunk_req",CONFIG="lss:session_config",COLUMN="lss:voxel_column",RESPONSE="lss:batch_response",DIRTY="lss:dirty_columns";
    private final JavaPlugin plugin;
    private final WorldAccess access;
    private final Map<UUID,Peer> peers=new LinkedHashMap<>();
    private final Map<ColumnCache.Key,Long> revisions=new HashMap<>();
    private final Set<ColumnCache.Key> busy=new HashSet<>();
    private final ExecutorService workers=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"ArchLOD-Voxy");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicInteger active=new AtomicInteger();
    private final ColumnCache cache;
    private final RawSender raw;
    private long tick,errors;
    private UploadService uploads;
    private boolean closed;
    private static final class Peer {
        final UUID player;final int capabilities;
        SessionGate.Lease lease;
        final LinkedHashMap<Long,Long> wants=new LinkedHashMap<>();
        long receivedTick;int received;
        Peer(UUID player,int capabilities){this.player=player;this.capabilities=capabilities;}
    }
    public VoxyService(JavaPlugin plugin,WorldAccess access) {
        this.plugin=plugin;this.access=access;
        try {
            raw=new RawSender();
            cache=new ColumnCache(plugin.getDataFolder().toPath().resolve("voxy-cache"),Duration.ofSeconds(Math.clamp(plugin.getConfig().getInt("lod.cache-max-age-seconds",-1),-1,3600)),-1);
        }catch(IOException|ReflectiveOperationException e){throw new IllegalStateException("Cannot initialize Voxy transport/cache",e);}
    }
    public void start() {
        var messenger=plugin.getServer().getMessenger();
        for(String c:List.of(HANDSHAKE,REQUEST,"lss:client_info","lss:region_summary_req"))messenger.registerIncomingPluginChannel(plugin,c,this);
        for(String c:List.of(CONFIG,COLUMN,RESPONSE,DIRTY))messenger.registerOutgoingPluginChannel(plugin,c);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
        uploads=new UploadService(plugin,access,raw,this);

    }
    public void onPluginMessageReceived(String channel,Player player,byte[] message) {
        if(closed||message.length>32768)return;
        try {
            var reader=new Wire.Reader(message);
            if(channel.equals(HANDSHAKE)) {
                int protocol=reader.varInt(),caps=reader.remaining()>0?reader.varInt():0;reader.end();
                if(protocol!=20)return;
                var old=peers.get(player.getUniqueId());
                if(old!=null && tick-old.receivedTick<20)return;
                var peer=new Peer(player.getUniqueId(),caps);peer.receivedTick=tick;
                if((caps&1)==0){config(player,peer,false);return;}
                peer.lease=access.issue(player.getUniqueId(),player.getWorld().getUID());
                peers.put(player.getUniqueId(),peer);config(player,peer,peer.lease!=null);return;
            }
            if(!channel.equals(REQUEST))return;
            var peer=peers.get(player.getUniqueId());if(peer==null||!access.valid(peer.lease))return;
            if(peer.receivedTick!=tick){peer.receivedTick=tick;peer.received=0;}
            if(++peer.received>8)return;
            int n=reader.count(1024);
            int max=Math.clamp(plugin.getConfig().getInt("lod.max-queued-per-player",256),16,1024);
            for(int i=0;i<n;i++) {
                long pos=reader.i64(),timestamp=reader.i64();
                if(peer.wants.size()<max && access.inRange(peer.lease,Wire.x(pos),Wire.z(pos)))peer.wants.putIfAbsent(pos,timestamp);
            }
            reader.end();
        }catch(IllegalArgumentException e){/* Malformed clients receive no data and cannot allocate unbounded work. */}
    }
    private void config(Player player,Peer peer,boolean enabled) {
        var out=new Wire.Writer().varInt(20).bool(enabled).varInt(radius()).bool(false).varInt(Bukkit.getUnsafe().getDataVersion());
        if((peer.capabilities&8)!=0)out.varInt(0);
        raw.send(player,CONFIG,out.result());
    }
    private int radius(){return Math.clamp(plugin.getConfig().getInt("lod.radius-chunks",128),8,512);}
    private void tick() {
        if(closed)return;tick++;
        int budget=Math.clamp(plugin.getConfig().getInt("lod.columns-per-tick",2),1,8);
        var ordered=new ArrayList<>(peers.values());
        if(!ordered.isEmpty())Collections.rotate(ordered,-(int)(tick%ordered.size()));
        for(Peer peer:ordered) {
            Player p=Bukkit.getPlayer(peer.player);
            if(p==null||!p.isOnline()){peers.remove(peer.player);continue;}
            var current=access.issue(peer.player,p.getWorld().getUID());
            if(!Objects.equals(peer.lease,current)) {
                peer.lease=current;peer.wants.clear();config(p,peer,current!=null);
            }
            if(current==null||budget<=0||active.get()>=4||peer.wants.isEmpty())continue;
            var want=peer.wants.entrySet().iterator().next();
            long pos=want.getKey(),stamp=want.getValue();peer.wants.remove(pos);
            var key=new ColumnCache.Key(current.world(),Wire.x(pos),Wire.z(pos));
            if(!access.inRange(current,key.x(),key.z())||busy.contains(key))continue;
            busy.add(key);active.incrementAndGet();budget--;
            long revision=revisions.getOrDefault(key,0L);var lease=current;
            try {workers.execute(()-> {
                var hit=cache.get(key);
                main(()->{if(hit==null)load(lease,key,revision,stamp);else deliver(lease,key,revision,stamp,hit);});
            });}catch(RejectedExecutionException e){finish(key);}
        }

    }
    private void load(SessionGate.Lease lease,ColumnCache.Key key,long revision,long clientStamp) {
        if(!access.inRange(lease,key.x(),key.z())||revision!=revisions.getOrDefault(key,0L)){finish(key);return;}
        World world=Bukkit.getWorld(key.world());if(world==null){finish(key);return;}
        if(!world.isChunkGenerated(key.x(),key.z())){answer(lease,key,(byte)2);finish(key);return;}
        // gen=false is essential: remote render requests must never create terrain.
        if(!world.isChunkLoaded(key.x(),key.z()))BackgroundChunks.loaded(world.getUID(),key.x(),key.z());
        world.getChunkAtAsync(key.x(),key.z(),false).whenComplete((chunk,error)->main(()-> {
            if(error!=null||chunk==null){answer(lease,key,(byte)2);finish(key);return;}
            if(!access.inRange(lease,key.x(),key.z())||revision!=revisions.getOrDefault(key,0L)){finish(key);return;}
            var snapshot=chunk.getChunkSnapshot(true,true,false);int min=world.getMinHeight(),max=world.getMaxHeight();
            try{workers.execute(()-> {
                try {
                    var entry=new ColumnCache.Entry(System.currentTimeMillis(),ColumnEncoder.encode(snapshot,min,max));
                    main(()-> {
                        if(revision!=revisions.getOrDefault(key,0L)){finish(key);return;}
                        try{workers.execute(()->{try{if(access.cacheAllowed(key.world()))cache.put(key,entry);}catch(IOException e){warn(e);}});}catch(RejectedExecutionException ignored){}
                        deliver(lease,key,revision,clientStamp,entry);
                    });
                }catch(RuntimeException e){warn(e);main(()->finish(key));}
            });}catch(RejectedExecutionException e){finish(key);}
        }));
    }
    private void deliver(SessionGate.Lease lease,ColumnCache.Key key,long revision,long stamp,ColumnCache.Entry entry) {
        try {
            if(revision!=revisions.getOrDefault(key,0L)||!access.inRange(lease,key.x(),key.z()))return;
            if(stamp>0 && stamp>=entry.timestamp()){answer(lease,key,(byte)1);return;}
            World w=Bukkit.getWorld(key.world());if(w==null)return;
            byte[] frame=new Wire.Writer().i32(key.x()).i32(key.z()).utf(w.getKey().toString()).i64(entry.timestamp()).u8(3).u8(0).bytes(entry.body()).result();
            access.send(lease,()->raw.send(Bukkit.getPlayer(lease.player()),COLUMN,frame));
        }catch(RuntimeException e){warn(e);}finally{finish(key);}
    }
    private void answer(SessionGate.Lease lease,ColumnCache.Key key,byte response) {
        byte[] frame=new Wire.Writer().varInt(1).u8(response).i64(Wire.pack(key.x(),key.z())).result();
        access.send(lease,()->raw.send(Bukkit.getPlayer(lease.player()),RESPONSE,frame));
    }
    private void finish(ColumnCache.Key key){if(busy.remove(key))active.decrementAndGet();}
    private void main(Runnable r){if(!closed && plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{if(!closed)r.run();});}
    private void warn(Exception e){synchronized(this){if(++errors==1||errors%100==0)plugin.getLogger().warning("Voxy cache/transport: "+e+" ("+errors+")");}}
    public void dirty(World world,int cx,int cz) {
        BackgroundChunks.changed(world.getUID(),cx,cz);
        var key=new ColumnCache.Key(world.getUID(),cx,cz);revisions.merge(key,1L,Long::sum);
        try{workers.execute(()->{try{cache.invalidate(key);}catch(IOException e){warn(e);}});}catch(RejectedExecutionException ignored){}
        byte[] message=new Wire.Writer().varInt(1).i64(Wire.pack(cx,cz)).result();
        for(Peer peer:peers.values())if(peer.lease!=null&&peer.lease.world().equals(world.getUID())&&access.inRange(peer.lease,cx,cz))
            access.send(peer.lease,()->raw.send(Bukkit.getPlayer(peer.player),DIRTY,message));
    }
    private void dirty(org.bukkit.block.Block b){dirty(b.getWorld(),b.getX()>>4,b.getZ()>>4);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void place(BlockPlaceEvent e){dirty(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void breakBlock(BlockBreakEvent e){dirty(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void explode(BlockExplodeEvent e){e.blockList().forEach(this::dirty);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void piston(BlockPistonExtendEvent e){e.getBlocks().forEach(b->{dirty(b);dirty(b.getRelative(e.getDirection()));});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void retract(BlockPistonRetractEvent e){e.getBlocks().forEach(b->{dirty(b);dirty(b.getRelative(e.getDirection()));});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void bucket(PlayerBucketEmptyEvent e){dirty(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void fill(PlayerBucketFillEvent e){dirty(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR)public void unload(WorldUnloadEvent e){for(Peer p:peers.values())if(p.lease!=null&&p.lease.world().equals(e.getWorld().getUID()))p.wants.clear();}
    public void wantsUpload(ColumnCache.Key key,long claimed,java.util.function.Consumer<Boolean> result) {
        try{workers.execute(()->{var hit=cache.get(key);main(()->result.accept(hit==null || (claimed>hit.timestamp() && plugin.getConfig().getBoolean("upload.trusted-client-timestamps",false))));});}
        catch(RejectedExecutionException e){result.accept(false);}
    }
    public void mergeUpload(SessionGate.Lease lease,ColumnCache.Key key,dev.vox.lss.common.wire.WireSectionCursor.WireColumn uploaded,java.util.function.Consumer<Boolean> result) {
        if(!access.inRange(lease,key.x(),key.z())){result.accept(false);return;}
        World world=Bukkit.getWorld(key.world());if(world==null||!world.isChunkGenerated(key.x(),key.z())){result.accept(false);return;}
        long revision=revisions.getOrDefault(key,0L);
        if(!world.isChunkLoaded(key.x(),key.z()))BackgroundChunks.loaded(world.getUID(),key.x(),key.z());
        world.getChunkAtAsync(key.x(),key.z(),false).whenComplete((chunk,error)->main(()->{
            if(error!=null||chunk==null||!access.inRange(lease,key.x(),key.z())){result.accept(false);return;}
            var snapshot=chunk.getChunkSnapshot(true,true,false);int min=world.getMinHeight(),max=world.getMaxHeight();
            try{workers.execute(()->{
                try {
                    // Missing Voxy sections are unknown, never air. Canonical data fills all gaps.
                    // Cached client data must agree with the current authoritative saved world.
                    byte[] canonical=ColumnEncoder.encode(snapshot,min,max);
                    var actual=ColumnValidation.validate(canonical,min/16,max/16);
                    if(!matchingSections(uploaded,actual)){main(()->result.accept(false));return;}
                    main(()->{
                        if(!access.valid(lease)||revision!=revisions.getOrDefault(key,0L)){result.accept(false);return;}
                        var entry=new ColumnCache.Entry(System.currentTimeMillis(),canonical);
                        try{workers.execute(()->{try{if(access.cacheAllowed(key.world()))cache.put(key,entry);main(()->result.accept(true));}catch(IOException e){warn(e);main(()->result.accept(false));}});}
                        catch(RejectedExecutionException e){result.accept(false);}
                    });
                }catch(RuntimeException e){main(()->result.accept(false));}
            });}catch(RejectedExecutionException e){result.accept(false);}
        }));
    }
    private static boolean matchingSections(dev.vox.lss.common.wire.WireSectionCursor.WireColumn uploaded,dev.vox.lss.common.wire.WireSectionCursor.WireColumn actual) {
        if(uploaded.sections().isEmpty())return false;
        for(var u:uploaded.sections()) {
            var a=actual.sections().stream().filter(s->s.sectionY()==u.sectionY()).findFirst().orElse(null);if(a==null)return false;
            int[] uv=ColumnValidation.values(u.blocks(),4096),av=ColumnValidation.values(a.blocks(),4096);
            for(int i=0;i<4096;i++)if(!uploaded.dictionary().get(uv[i]).equals(actual.dictionary().get(av[i])))return false;
        }
        return true;
    }
    public ColumnCache cache(){return cache;}
    public CompletableFuture<Boolean> refreshSnapshot(World world,org.bukkit.ChunkSnapshot snapshot){
        var result=new CompletableFuture<Boolean>();var key=new ColumnCache.Key(world.getUID(),snapshot.getX(),snapshot.getZ());
        if(!access.cacheAllowed(key.world())){result.complete(false);return result;}
        long rev=revisions.getOrDefault(key,0L);int min=world.getMinHeight(),max=world.getMaxHeight();
        try{workers.execute(()->{try{
            var entry=new ColumnCache.Entry(System.currentTimeMillis(),ColumnEncoder.encode(snapshot,min,max));
            main(()->{
                if(!access.cacheAllowed(key.world())||rev!=revisions.getOrDefault(key,0L)){result.complete(false);return;}
                try{workers.execute(()->{try{if(access.cacheAllowed(key.world())){cache.put(key,entry);result.complete(true);}else result.complete(false);}catch(Exception e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}
            });
        }catch(Exception e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}return result;
    }
    public CompletableFuture<Boolean> warm(World world,int x,int z){
        var done=new CompletableFuture<Boolean>();if(!access.cacheAllowed(world.getUID())||!world.isChunkGenerated(x,z)){done.complete(false);return done;}
        if(!world.isChunkLoaded(x,z))BackgroundChunks.loaded(world.getUID(),x,z);
        world.getChunkAtAsync(x,z,false).whenComplete((chunk,error)->main(()->{
            if(error!=null){done.completeExceptionally(error);return;}if(chunk==null){done.complete(false);return;}
            refreshSnapshot(world,chunk.getChunkSnapshot(true,true,false)).whenComplete((ok,e)->{if(e!=null)done.completeExceptionally(e);else done.complete(ok);});
        }));return done;
    }
    public UploadService uploads(){return uploads;}
    public void close(){if(uploads!=null)uploads.close();closed=true;peers.clear();workers.shutdownNow();HandlerList.unregisterAll(this);}
}
