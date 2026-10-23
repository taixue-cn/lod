package cc.taixue.lod.client;

import cc.taixue.lod.core.*;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Uploads require both an explicit server policy and a locally trusted server address. */
final class CacheExchange {
    private final BiConsumer<String,byte[]> send;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"TaixueLOD-cache-offers");t.setDaemon(true);return t;});
    private final Set<String> trustedAddresses=new HashSet<>();
    private boolean permitted,working;
    private long nonce,offeredPos,lastOffer;
    private byte[] offered;
    private int epoch;
    CacheExchange(BiConsumer<String,byte[]> send) {
        this.send=send;
        Path config=FabricLoader.getInstance().getConfigDir().resolve("taixue-lod-client.json");
        try {
            if(!Files.exists(config))Files.writeString(config,"{\"shareLocalCache\":true,\"trustedServers\":[\"mc.taixue.cc\",\"minecraft.taixue.cc\",\"*.taixue.cc\"]}\n");
            var json=JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            if(json.has("shareLocalCache")&&json.get("shareLocalCache").getAsBoolean())for(var s:json.getAsJsonArray("trustedServers"))trustedAddresses.add(s.getAsString().toLowerCase(Locale.ROOT));
        }catch(Exception ignored){trustedAddresses.clear();}
    }
    void reset(){epoch++;permitted=false;offered=null;working=false;lastOffer=0;}
    void policy(byte[] frame) {
        reset();var r=new Wire.Reader(frame);if(r.varInt()!=1)return;
        String serverId=r.utf(128);r.utf(64);String dimension=r.utf(256);nonce=r.i64();boolean uploads=r.bool();r.end();
        var client=Minecraft.getInstance();var info=client.getCurrentServer();if(info==null||client.level==null)return;
        String host=info.ip.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$","");
        boolean trusted=trustedAddresses.contains(host)||trustedAddresses.stream().filter(s->s.startsWith("*.")).anyMatch(s->host.endsWith(s.substring(1)));
        permitted=uploads&&trusted&&serverId.equals("taixue-arch")&&client.level.dimension().identifier().toString().equals(dimension);
    }
    void consider(int x,int z) {
        if(!permitted||working||System.currentTimeMillis()-lastOffer<5000)return;
        var client=Minecraft.getInstance();var level=client.level;if(level==null)return;
        int generation=epoch;working=true;lastOffer=System.currentTimeMillis();long pos=Wire.pack(x,z);
        worker.execute(()->{
            byte[] bytes=null;try{bytes=VoxyCacheReader.read(level,x,z);}catch(ReflectiveOperationException|RuntimeException ignored){}
            byte[] result=bytes;
            client.execute(()-> {
                if(generation!=epoch)return;working=false;
                if(result==null||result.length>524288||client.level!=level)return;
                offered=result;offeredPos=pos;
                try {
                    // Voxy does not record a reliable per-section wall-clock time. Zero means unknown.
                    byte[] digest=MessageDigest.getInstance("SHA-256").digest(result);
                    send.accept("taixue:lod_offer",new Wire.Writer().i64(nonce).i64(pos).i64(0).varInt(result.length).raw(digest).result());
                }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            });
        });
    }
    void want(byte[] frame) {
        var r=new Wire.Reader(frame);long n=r.i64(),p=r.i64();r.end();
        if(!permitted||n!=nonce||p!=offeredPos||offered==null||System.currentTimeMillis()-lastOffer>15000)return;
        for(int offset=0;offset<offered.length;offset+=28000) {
            byte[] part=Arrays.copyOfRange(offered,offset,Math.min(offered.length,offset+28000));
            send.accept("taixue:lod_part",new Wire.Writer().i64(nonce).i64(p).i32(offset).bytes(part).result());
        }
        offered=null;
    }
}
