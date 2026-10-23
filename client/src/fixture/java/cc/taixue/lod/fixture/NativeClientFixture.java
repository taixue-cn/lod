package cc.taixue.lod.fixture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;

/** Included only in the explicitly enabled Loom fixture run, never in the player jar. */
public final class NativeClientFixture implements ClientModInitializer {
    private long started;
    private int ticks;
    private boolean done,connecting;
    private volatile boolean dhReading,dhGold;
    private volatile String dhError="";
    private Object dhCache;
    public void onInitializeClient() {
        started=System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(done)return;
            if(++ticks%20!=0)return;
            try {
                if(client.level==null){
                    if(!connecting&&ticks>=100){
                        connecting=true;String address=System.getenv("ARCH_LOD_NATIVE_SERVER");
                        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),client,net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),new net.minecraft.client.multiplayer.ServerData("isolated",address,net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null);
                    }
                    if(System.currentTimeMillis()-started>120000)finish(client,false,"no multiplayer world");return;}
                String mode=System.getProperty("archlod.fixture.mode","voxy");
                if(mode.equals("fallback")&&ticks>400){finish(client,true,"normal multiplayer world with no server handshake");return;}
                if(mode.equals("voxy")) {
                    bridgeDebug();
                    client.options.renderDistance().set(2);
                    var type=Class.forName("cc.taixue.lod.client.VoxyCacheReader");var read=type.getDeclaredMethod("read",ClientLevel.class,int.class,int.class);read.setAccessible(true);
                    byte[] body=(byte[])read.invoke(null,client.level,4,4);
                    boolean vanillaLoaded=client.level.getChunkSource().hasChunk(4,4);
                    if(body!=null && new String(body,StandardCharsets.ISO_8859_1).contains("minecraft:gold_block") && !vanillaLoaded && acceptedColumn()){finish(client,true,"unmodified Voxy engine contains remote gold block outside vanilla loaded chunks");return;}
                }
                if(mode.equals("dh")) {
                    dhDebug();
                    if(dhGold&&!client.level.getChunkSource().hasChunk(4,4)){finish(client,true,"unmodified DH terrain API contains remote gold block outside vanilla loaded chunks");return;}
                    if(!dhReading){dhReading=true;var worker=new Thread(()->{try{dhGold=dhGold();}catch(Throwable error){dhError=error.toString()+" "+error.getCause();}finally{dhReading=false;}},"DH-fixture-query");worker.setDaemon(true);worker.start();}
                }
                if(System.currentTimeMillis()-started>120000)finish(client,false,"remote terrain not observed: "+dhError);
            }catch(Throwable error){finish(client,false,error.toString());}
        });
    }
    private void bridgeDebug() throws Exception {
        var lines=new java.util.ArrayList<String>();
        for(var container:net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("client",ClientModInitializer.class)) {
            Object entry=container.getEntrypoint();if(!entry.getClass().getName().equals("cc.taixue.lod.client.LodClient"))continue;
            for(String name:java.util.List.of("enabled","handshook","ticks","cursor","errors","pending","timestamps")) {
                var f=entry.getClass().getDeclaredField(name);f.setAccessible(true);Object value=f.get(entry);
                lines.add(name+": "+(value instanceof java.util.Map<?,?> map? "size="+map.size()+", target="+map.get((4L<<32)|4L):value));
            }
        }
        Files.write(Path.of(System.getProperty("archlod.fixture.report")+".debug"),lines);
    }
    private void dhDebug(){try{
        var api=Class.forName("com.seibel.distanthorizons.core.api.internal.SharedApi");Object world=api.getMethod("getAbstractDhWorld").invoke(null);
        var lines=new java.util.ArrayList<String>();if(world!=null){
            world.getClass().getMethod("addDebugMenuStringsToList",java.util.List.class).invoke(world,lines);
            Object state=world.getClass().getField("networkState").get(world);lines.add("session: "+state.getClass().getField("sessionConfig").get(state));
            for(Object level:(Iterable<?>)world.getClass().getMethod("getAllLoadedLevels").invoke(world))level.getClass().getMethod("addDebugMenuStringsToList",java.util.List.class).invoke(level,lines);
        }lines.add("API error: "+dhError);
        Files.write(Path.of(System.getProperty("archlod.fixture.report")+".debug"),lines);
    }catch(Exception ignored){}}
    private boolean acceptedColumn() throws Exception {
        for(var container:net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("client",ClientModInitializer.class)){
            Object entry=container.getEntrypoint();if(!entry.getClass().getName().equals("cc.taixue.lod.client.LodClient"))continue;
            var field=entry.getClass().getDeclaredField("timestamps");field.setAccessible(true);
            return ((java.util.Map<?,?>)field.get(entry)).containsKey((4L<<32)|4L);
        }
        return false;
    }
    private boolean dhGold() throws Exception {
        var delayed=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
        Object proxy=delayed.getField("worldProxy").get(null),repo=delayed.getField("terrainRepo").get(null);
        if(proxy==null||repo==null)return false;
        var worldApi=Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
        var levelApi=Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
        var cacheApi=Class.forName("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache");
        var repoApi=Class.forName("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo");
        dhCache=repoApi.getMethod("createSoftCache").invoke(repo);
        var levels=(Iterable<?>)worldApi.getMethod("getAllLoadedLevelWrappers").invoke(proxy);
        for(Object level:levels){
            Object result=repoApi.getMethod("getSingleDataPointAtBlockPos",levelApi,int.class,int.class,int.class,cacheApi).invoke(repo,level,64,64,64,dhCache);
            if(!result.getClass().getField("success").getBoolean(result)){dhError=result.getClass().getField("message").get(result).toString();continue;}
            Object point=result.getClass().getField("payload").get(result);if(point==null)continue;
            Object block=point.getClass().getField("blockStateWrapper").get(point);
            var blockApi=Class.forName("com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper");
            if(blockApi.getMethod("getSerialString").invoke(block).toString().contains("gold_block"))return true;
        }
        return false;
    }
    private void finish(Minecraft client,boolean pass,String detail){
        done=true;
        try{
            Path p=Path.of(System.getProperty("archlod.fixture.report"));Files.createDirectories(p.getParent());
            var j=new com.google.gson.JsonObject();j.addProperty("status",pass?"PASS":"FAIL");j.addProperty("detail",detail);j.addProperty("ticks",ticks);j.addProperty("production_server",false);Files.writeString(p,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(j));
        }catch(Exception e){throw new IllegalStateException(e);}
        client.stop();
    }
}
