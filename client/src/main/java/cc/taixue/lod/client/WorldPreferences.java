package cc.taixue.lod.client;

import cc.taixue.lod.core.*;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;

/** Per-player, per-server download choices; the server's world policy always wins. */
final class WorldPreferences {
    private final Path path=FabricLoader.getInstance().getConfigDir().resolve("taixue-lod-worlds.json");
    private JsonObject profiles=new JsonObject();
    WorldPreferences(){try{if(Files.exists(path))profiles=JsonParser.parseString(Files.readString(path)).getAsJsonObject();}catch(Exception ignored){}registerCommands();}
    private String address(){var s=Minecraft.getInstance().getCurrentServer();return s==null?"singleplayer":s.ip.toLowerCase(Locale.ROOT);}
    private JsonObject current(){String key=address();if(!profiles.has(key)){var p=new JsonObject();p.addProperty("mode","blacklist");p.add("entries",new JsonArray());profiles.add(key,p);}return profiles.getAsJsonObject(key);}
    private List<String> entries(){var out=new ArrayList<String>();for(var e:current().getAsJsonArray("entries"))out.add(e.getAsString());return out;}
    boolean allows(){var l=Minecraft.getInstance().level;if(l==null)return false;var id=l.dimension().identifier();try{return new WorldPolicy(current().get("mode").getAsString(),entries()).allows(id.toString(),id.getPath());}catch(RuntimeException e){return false;}}
    int radius(){return Math.clamp(current().has("radiusChunks")?current().get("radiusChunks").getAsInt():64,8,128);}
    byte[] frame(){var out=new Wire.Writer().utf(current().get("mode").getAsString());var es=entries();out.varInt(es.size());for(String e:es)out.utf(e);return out.result();}
    private void save(){try{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(profiles));}catch(java.io.IOException e){throw new IllegalStateException("Cannot save LOD preferences",e);}}
    private void say(String s){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(s),false);}
    private int mode(String value){current().addProperty("mode",value);save();say("LOD downloads: "+value+" "+entries());return 1;}
    private int edit(boolean add,String value){
        if(value.equals("current")){var l=Minecraft.getInstance().level;if(l==null)return 0;value=l.dimension().identifier().toString();}
        var es=entries();if(add){if(es.size()>=64){say("At most 64 world entries");return 0;}if(!es.contains(value))es.add(value);}else es.remove(value);
        var array=new JsonArray();es.forEach(array::add);current().add("entries",array);save();say("LOD downloads: "+current().get("mode").getAsString()+" "+es);return 1;
    }
    private void registerCommands(){ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(literal("lod-c")
        .then(literal("radius").executes(ctx->{say("LOD download radius: "+radius()+" chunks");return 1;})
            .then(argument("chunks",IntegerArgumentType.integer(8,128)).executes(ctx->{current().addProperty("radiusChunks",IntegerArgumentType.getInteger(ctx,"chunks"));save();say("LOD download radius: "+radius()+" chunks");return 1;})))
        .then(literal("worlds").executes(ctx->{say(current().toString());return 1;})
            .then(literal("mode").then(literal("whitelist").executes(ctx->mode("whitelist"))).then(literal("blacklist").executes(ctx->mode("blacklist"))))
            .then(literal("add").then(argument("world",StringArgumentType.greedyString()).executes(ctx->edit(true,StringArgumentType.getString(ctx,"world")))))
            .then(literal("remove").then(argument("world",StringArgumentType.greedyString()).executes(ctx->edit(false,StringArgumentType.getString(ctx,"world"))))))));}
}
