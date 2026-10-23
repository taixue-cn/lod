package cc.taixue.lod.client;

import cc.taixue.lod.core.Wire;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.LoggerFactory;
import java.util.*;

/** An optional LSS-v20 receiver; never changes vanilla chunk requests or Voxy settings. */
public final class LodClient implements ClientModInitializer {
    private static final String HANDSHAKE="lss:handshake_c2s",INFO="lss:client_info",REQUEST="lss:batch_chunk_req";
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger("TaixueLOD");
    private final Map<String,CustomPacketPayload.Type<Frame>> types=new HashMap<>();
    private final LinkedHashMap<Long,Long> timestamps=new LinkedHashMap<>();
    private final Map<Long,Long> pending=new HashMap<>();
    private VoxyBridge bridge;
    private CacheExchange exchange;
    private WorldPreferences preferences;
    private byte[] lastPreference;
    private ClientLevel level;
    private boolean enabled,handshook;
    private int distance=64,ticks,cursor;
    private long errors;
    public void onInitializeClient() {
        var loader=net.fabricmc.loader.api.FabricLoader.getInstance();
        if(loader.isModLoaded("lss")||loader.isModLoaded("lod_server_support")||loader.isModLoaded("voxy_server_side")) {
            LOG.info("Public LSS/VSS installed; yielding all terrain channels to it");return;
        }
        try{bridge=new VoxyBridge();}catch(ReflectiveOperationException|LinkageError e){LOG.info("Voxy unavailable; server terrain bridge remains inactive");}
        for(String c:List.of(HANDSHAKE,INFO,REQUEST,"taixue:lod_hello","taixue:lod_offer","taixue:lod_part","taixue:lod_pref")) {
            var type=Frame.type(c);types.put(c,type);PayloadTypeRegistry.playC2S().register(type,Frame.codec(type,32768));
        }
        for(String c:List.of("lss:session_config","lss:voxel_column","lss:batch_response","lss:dirty_columns","taixue:lod_policy","taixue:lod_want")) {
            var type=Frame.type(c);types.put(c,type);PayloadTypeRegistry.playS2C().register(type,Frame.codec(type,Wire.MAX_FRAME));
            ClientPlayNetworking.registerGlobalReceiver(type,(frame,ctx)->ctx.client().execute(()->receive(c,frame.bytes())));
        }
        preferences=new WorldPreferences();exchange=new CacheExchange(this::send);
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("Taixue LOD bridge initialized; native Voxy/DH behavior remains available");
    }
    private void reset(){if(exchange!=null)exchange.reset();enabled=false;handshook=false;lastPreference=null;level=null;ticks=0;cursor=0;timestamps.clear();pending.clear();}
    private void tick(Minecraft client) {
        ticks++;if(client.level==null||client.player==null)return;
        if(ticks%10==0 && ClientPlayNetworking.canSend(types.get("taixue:lod_pref"))) {
            byte[] pref=preferences.frame();if(!Arrays.equals(pref,lastPreference)){send("taixue:lod_pref",pref);lastPreference=pref;}
        }
        if(client.level!=level){if(exchange!=null)exchange.reset();level=client.level;timestamps.clear();pending.clear();cursor=0;if(handshook)send("taixue:lod_hello",new Wire.Writer().varInt(1).result());}
        if(bridge!=null && !handshook && ticks%20==0 && ClientPlayNetworking.canSend(types.get(HANDSHAKE))) {
            send(INFO,new Wire.Writer().varInt(net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()).result());
            send(HANDSHAKE,new Wire.Writer().varInt(20).varInt(1).result());send("taixue:lod_hello",new Wire.Writer().varInt(1).result());handshook=true;
        }
        if(bridge==null||!enabled||!preferences.allows()||ticks%4!=0)return;
        int px=client.player.chunkPosition().x,pz=client.player.chunkPosition().z;
        int radius=Math.min(distance,preferences.radius());
        int diameter=radius*2+1,total=diameter*diameter;
        var wants=new ArrayList<Long>();long now=System.currentTimeMillis();
        for(int checked=0;checked<512 && wants.size()<16;checked++) {
            if(cursor>=total)cursor=0;
            int[] offset=spiral(cursor++);int x=px+offset[0],z=pz+offset[1];
            if(Math.abs(offset[0])>radius||Math.abs(offset[1])>radius)continue;
            // Vanilla owns nearby chunks; the bridge supplements distant terrain only.
            if(Math.abs(offset[0])<=client.options.getEffectiveRenderDistance() && Math.abs(offset[1])<=client.options.getEffectiveRenderDistance())continue;
            long p=Wire.pack(x,z);
            if(now-pending.getOrDefault(p,0L)<15000)continue;
            Long previous=timestamps.get(p);if(previous!=null && now-previous<60000)continue;
            exchange.consider(x,z);
            wants.add(p);pending.put(p,now);
        }
        if(!wants.isEmpty()) {
            var out=new Wire.Writer().varInt(wants.size());for(long p:wants)out.i64(p).i64(timestamps.getOrDefault(p,0L));send(REQUEST,out.result());
        }
        while(timestamps.size()>150000)timestamps.remove(timestamps.keySet().iterator().next());
        if(pending.size()>20000)pending.entrySet().removeIf(e->now-e.getValue()>15000);
    }
    private void receive(String channel,byte[] data) {
        try {
            if(channel.equals("taixue:lod_policy")){exchange.policy(data);return;}
            if(channel.equals("taixue:lod_want")){exchange.want(data);return;}
            var r=new Wire.Reader(data);
            if(channel.equals("lss:session_config")) {
                if(r.varInt()!=20)return;enabled=r.bool();distance=Math.clamp(r.varInt(),8,128);r.bool();
                if(r.remaining()>0)r.varInt();if(r.remaining()>0)r.varInt();r.end();
                if(!enabled)pending.clear();return;
            }
            if(!enabled||!preferences.allows()||level==null||Minecraft.getInstance().level!=level)return;
            if(channel.equals("lss:voxel_column")) {
                int x=r.i32(),z=r.i32();String dimension=r.utf(256);long stamp=r.i64();r.u8();int codec=r.u8();byte[] body=r.bytes(Wire.MAX_FRAME);r.end();
                if(codec!=0 || !level.dimension().identifier().toString().equals(dimension))return;
                long p=Wire.pack(x,z);if(!pending.containsKey(p))return;
                if(bridge.ingest(level,x,z,body)){timestamps.put(p,stamp);pending.remove(p);}
            } else if(channel.equals("lss:batch_response")) {
                int count=r.count(4096);for(int i=0;i<count;i++){int type=r.u8();long p=r.i64();if(type==1||type==2){pending.remove(p);timestamps.put(p,System.currentTimeMillis());}}r.end();
            } else if(channel.equals("lss:dirty_columns")) {
                int count=r.count(10000);for(int i=0;i<count;i++){long p=r.i64();timestamps.remove(p);pending.remove(p);}r.end();
            }
        }catch(ReflectiveOperationException|RuntimeException e){if(++errors==1||errors%100==0)LOG.warn("LOD frame could not be ingested ({}): {}",errors,e.toString());}
    }
    private void send(String channel,byte[] bytes){if((channel.equals("taixue:lod_offer")||channel.equals("taixue:lod_part"))&&!preferences.allows())return;var type=types.get(channel);if(ClientPlayNetworking.canSend(type))ClientPlayNetworking.send(new Frame(type,bytes));}
    static int[] spiral(int index) {
        if(index==0)return new int[]{0,0};
        int ring=(int)Math.ceil((Math.sqrt(index+1)-1)/2);int side=2*ring,offset=index-(2*ring-1)*(2*ring-1);
        if(offset<side)return new int[]{ring,-ring+1+offset};offset-=side;
        if(offset<side)return new int[]{ring-1-offset,ring};offset-=side;
        if(offset<side)return new int[]{-ring,ring-1-offset};offset-=side;
        return new int[]{-ring+1+offset,-ring};
    }
}
