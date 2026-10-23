package cc.taixue.lod.server;

import cc.taixue.lod.core.*;
import dev.vox.lss.common.wire.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Opt-in, current-world-only cache exchange. The server must request each upload first. */
public final class UploadService implements PluginMessageListener {
    public static final String HELLO="taixue:lod_hello",POLICY="taixue:lod_policy",OFFER="taixue:lod_offer",WANT="taixue:lod_want",PART="taixue:lod_part",PREF="taixue:lod_pref";
    private final JavaPlugin plugin;
    private final WorldAccess access;
    private final RawSender raw;
    private final VoxyService terrain;
    private final SecureRandom random=new SecureRandom();
    private final Map<UUID,Peer> peers=new HashMap<>();
    private final Map<UUID,Incoming> incoming=new HashMap<>();
    private long accepted,rejected;
    private final Map<UUID,long[]> rates=new HashMap<>();
    private record Peer(SessionGate.Lease lease,long nonce){}
    private static final class Incoming {
        final SessionGate.Lease lease;final long nonce,pos,claimedTime,deadline;
        final byte[] hash,body;
        int received;
        Incoming(Peer peer,long pos,long stamp,byte[] hash,int size){this.lease=peer.lease;this.nonce=peer.nonce;this.pos=pos;this.claimedTime=stamp;this.hash=hash;this.body=new byte[size];deadline=System.currentTimeMillis()+15000;}
    }
    private final Map<UUID,Long> lastOffers=new HashMap<>();
    public UploadService(JavaPlugin plugin,WorldAccess access,RawSender raw,VoxyService terrain) {
        this.plugin=plugin;this.access=access;this.raw=raw;this.terrain=terrain;
        var m=Bukkit.getMessenger();for(String c:List.of(HELLO,OFFER,PART,PREF))m.registerIncomingPluginChannel(plugin,c,this);
        for(String c:List.of(POLICY,WANT))m.registerOutgoingPluginChannel(plugin,c);
        Bukkit.getScheduler().runTaskTimer(plugin,()-> {
            incoming.entrySet().removeIf(e->System.currentTimeMillis()>e.getValue().deadline||!access.valid(e.getValue().lease));
            peers.entrySet().removeIf(e->!access.valid(e.getValue().lease));
            lastOffers.keySet().removeIf(id->Bukkit.getPlayer(id)==null);
            rates.keySet().removeIf(id->Bukkit.getPlayer(id)==null);
        },20,20);
    }
    private boolean trusted(Player p) {
        if(!plugin.getConfig().getBoolean("upload.enabled",false))return false;
        var blocked=plugin.getConfig().getStringList("upload.blocked-players");
        if(matches(blocked,p))return false;
        return matches(plugin.getConfig().getStringList("upload.allowed-players"),p)||p.hasPermission("archlod.upload");
    }
    private static boolean matches(List<String> names,Player p){return names.stream().anyMatch(n->n.equals("*")||n.equalsIgnoreCase(p.getName())||n.equalsIgnoreCase(p.getUniqueId().toString()));}
    public void onPluginMessageReceived(String channel,Player player,byte[] message) {
        if(message.length>32768)return;
        var rate=rates.computeIfAbsent(player.getUniqueId(),id->new long[2]);long second=System.currentTimeMillis()/1000;
        if(rate[0]!=second){rate[0]=second;rate[1]=0;}if(++rate[1]>64)return;
        try {
            var r=new Wire.Reader(message);
            if(channel.equals(PREF)) {
                String mode=r.utf(16);int n=r.count(64);var entries=new ArrayList<String>();for(int i=0;i<n;i++)entries.add(r.utf(256));r.end();
                access.preference(player.getUniqueId(),new WorldPolicy(mode,entries));return;
            }
            if(channel.equals(HELLO)) {
                if(r.varInt()!=1)return;r.end();
                var lease=access.issue(player.getUniqueId(),player.getWorld().getUID());
                if(lease==null)return;
                var peer=new Peer(lease,random.nextLong());peers.put(player.getUniqueId(),peer);incoming.remove(player.getUniqueId());
                byte[] response=new Wire.Writer().varInt(1).utf("taixue-arch").utf(lease.world().toString()).utf(player.getWorld().getKey().toString()).i64(peer.nonce).bool(trusted(player)).result();
                access.send(lease,()->raw.send(player,POLICY,response));return;
            }
            var peer=peers.get(player.getUniqueId());
            if(peer==null||!access.valid(peer.lease)||!trusted(player))return;
            long nonce=r.i64(),pos=r.i64();if(nonce!=peer.nonce||!access.inRange(peer.lease,Wire.x(pos),Wire.z(pos)))return;
            if(channel.equals(OFFER)) {
                long time=r.i64();int size=r.count(Math.clamp(plugin.getConfig().getInt("upload.max-column-bytes",524288),1024,524288));byte[] hash=r.raw(32);r.end();
                long now=System.currentTimeMillis();
                if(time<0||time>now+60000||size<2||incoming.containsKey(player.getUniqueId()))return;
                long cooldown=Math.clamp(plugin.getConfig().getInt("upload.cooldown-seconds",5),1,60)*1000L;
                if(now-lastOffers.getOrDefault(player.getUniqueId(),0L)<cooldown)return;lastOffers.put(player.getUniqueId(),now);
                if(incoming.size()>=Math.clamp(plugin.getConfig().getInt("upload.max-inflight",4),1,16))return;
                terrain.wantsUpload(new ColumnCache.Key(peer.lease.world(),Wire.x(pos),Wire.z(pos)),time,wanted->{
                    if(!wanted||!access.valid(peer.lease)||!trusted(player)||peers.get(player.getUniqueId())!=peer||incoming.containsKey(player.getUniqueId())||incoming.size()>=Math.clamp(plugin.getConfig().getInt("upload.max-inflight",4),1,16))return;
                    incoming.put(player.getUniqueId(),new Incoming(peer,pos,time,hash,size));
                    access.send(peer.lease,()->raw.send(player,WANT,new Wire.Writer().i64(nonce).i64(pos).result()));
                });
            }else if(channel.equals(PART)) {
                var upload=incoming.get(player.getUniqueId());if(upload==null||upload.pos!=pos||upload.nonce!=nonce)return;
                int offset=r.i32();byte[] bytes=r.bytes(28000);r.end();
                if(offset!=upload.received||bytes.length==0||offset+bytes.length>upload.body.length||System.currentTimeMillis()>upload.deadline){incoming.remove(player.getUniqueId());rejected++;return;}
                System.arraycopy(bytes,0,upload.body,offset,bytes.length);upload.received+=bytes.length;
                if(upload.received==upload.body.length) {
                    incoming.remove(player.getUniqueId());
                    if(!MessageDigest.isEqual(upload.hash,MessageDigest.getInstance("SHA-256").digest(upload.body))){rejected++;return;}
                    World world=player.getWorld();
                    var column=ColumnValidation.validate(upload.body,world.getMinHeight()/16,world.getMaxHeight()/16);
                    validateIdentities(column);
                    terrain.mergeUpload(upload.lease,new ColumnCache.Key(upload.lease.world(),Wire.x(pos),Wire.z(pos)),column,ok->{if(ok)accepted++;else rejected++;});
                }
            }
        }catch(IllegalArgumentException|NoSuchAlgorithmException e){incoming.remove(player.getUniqueId());rejected++;}
    }
    private static void validateIdentities(WireSectionCursor.WireColumn column) {
        var checkedBlocks=new HashSet<Integer>();var checkedBiomes=new HashSet<Integer>();
        for(var s:column.sections()) {
            for(int id:s.blocks().palette())if(checkedBlocks.add(id))Bukkit.createBlockData(column.dictionary().get(id));
            for(int id:s.biomes().palette())if(checkedBiomes.add(id)) {
                var key=NamespacedKey.fromString(column.dictionary().get(id));
                if(key==null||Registry.BIOME.get(key)==null)throw new IllegalArgumentException("unknown biome");
            }
        }
    }
    public long accepted(){return accepted;}
    public long rejected(){return rejected;}
    public void close(){peers.clear();incoming.clear();lastOffers.clear();rates.clear();}
}
