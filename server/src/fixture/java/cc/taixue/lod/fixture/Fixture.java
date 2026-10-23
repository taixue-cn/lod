package cc.taixue.lod.fixture;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Only packaged into the isolated test jar; never deployed to ARCH. */
public final class Fixture extends JavaPlugin implements Listener {
    private World publicWorld,secret;
    private final Map<UUID,PermissionAttachment> attachments=new HashMap<>();
    public void onEnable() {
        if(!getServer().getIp().equals("127.0.0.1"))throw new IllegalStateException("Fixture requires loopback binding");
        publicWorld=Bukkit.getWorlds().getFirst();
        secret=new WorldCreator("lod-secret").type(WorldType.FLAT).generateStructures(false).generatorSettings("{\"biome\":\"minecraft:plains\",\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:stone\",\"height\":4}],\"features\":false}").createWorld();
        for(World w:List.of(publicWorld,secret)) {
            w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.SPAWN_CHUNK_RADIUS,0);
            w.setSpawnLocation(0,64,0);
            for(int x=4;x<8;x++)for(int z=4;z<8;z++)w.getChunkAt(x,z);
            w.getBlockAt(64,64,64).setType(w==secret?Material.DIAMOND_BLOCK:Material.GOLD_BLOCK);
        }
        getServer().getPluginManager().registerEvents(this,this);
        getCommand("lodfixture").setExecutor((sender,command,label,args)->{
            if(!(sender instanceof Player p)||args.length!=1)return false;
            var a=attachments.get(p.getUniqueId());if(a==null)return false;
            switch(args[0]) {
                case "cachetest" -> new CacheFixture(this,lod()).start();
                case "revoke" -> a.setPermission("multiverse.access."+p.getWorld().getName(),false);
                case "grant" -> a.setPermission("multiverse.access."+p.getWorld().getName(),true);
                case "secret" -> p.teleport(new Location(secret,0.5,64,0.5));
                case "public" -> p.teleport(new Location(publicWorld,0.5,64,0.5));
                case "grantsecret" -> a.setPermission("multiverse.access."+secret.getName(),true);
                case "clear" -> lod().getVoxyService().dirty(publicWorld,4,4);
                case "stats" -> {try{var u=lod().getVoxyService().uploads();java.nio.file.Files.writeString(java.nio.file.Path.of("upload-stats.json"),"{\"accepted\":"+u.accepted()+",\"rejected\":"+u.rejected()+"}");}catch(Exception error){throw new RuntimeException(error);}}
                case "globaldeny" -> policy("whitelist",List.of("lod-secret"));
                case "globalblacklist" -> policy("blacklist",List.of("world"));
                case "globalallow" -> policy("whitelist",List.of("world"));
                case "edit" -> {try{Class.forName("cc.taixue.lod.fixture.WorldEditFixture").getMethod("edit",String.class).invoke(null,publicWorld.getName());}catch(Exception e){throw new RuntimeException(e);}}
                case "mutate" -> publicWorld.getBlockAt(64,64,64).setType(Material.EMERALD_BLOCK);
                default -> {return false;}
            }
            p.sendMessage("FIXTURE:"+args[0]);return true;
        });
        getLogger().info("LOD_FIXTURE_READY");
    }
    private no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin lod(){return (no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin)Bukkit.getPluginManager().getPlugin("ArchLOD");}
    private void policy(String mode,List<String> entries){lod().getConfig().set("lod.worlds.mode",mode);lod().getConfig().set("lod.worlds.entries",entries);lod().getWorldAccess().refreshPolicy();}
    @EventHandler public void join(PlayerJoinEvent e){
        Player p=e.getPlayer();var a=p.addAttachment(this);attachments.put(p.getUniqueId(),a);
        a.setPermission("archlod.use",true);a.setPermission("archlod.upload",true);
        a.setPermission("multiverse.access."+publicWorld.getName(),!p.getName().equals("LODNo"));
        a.setPermission("multiverse.access."+secret.getName(),false);
    }
}
