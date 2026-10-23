package cc.taixue.lod.server;
import cc.taixue.lod.core.*;
import no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One retention policy spans the compressed Voxy files and DH model payloads. */
public final class CacheManager implements AutoCloseable,Listener {
 private final DhSupportBukkitPlugin plugin;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"LOD-cache-retention");t.setDaemon(true);return t;});
 private final Properties worlds=new Properties();
 private volatile Map<UUID,CacheRetention.Rule> rules=Map.of();
 private volatile long global;
 private volatile boolean running;
 private CompletableFuture<String> current;
 private long lastRun;
 private volatile String status="not scanned";
 public CacheManager(DhSupportBukkitPlugin plugin){this.plugin=plugin;
  try(var in=Files.newInputStream(index())){worlds.load(in);}catch(java.io.IOException ignored){}
  Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getWorlds().forEach(this::know);reload();
  Bukkit.getScheduler().runTaskTimer(plugin,()->{if(System.currentTimeMillis()-lastRun>=Math.max(1,plugin.getConfig().getInt("cache.maintenance-seconds",30))*1000L)trim();},20,20);
 }
 private Path index(){return plugin.getDataFolder().toPath().resolve("cached-worlds.properties");}
 private void know(World world){worlds.setProperty(world.getUID().toString(),world.getName());try(var out=Files.newOutputStream(index())){worlds.store(out,"Cached world UUIDs; retained when a world unloads");}catch(java.io.IOException e){plugin.getLogger().warning(e.toString());}}
 @EventHandler public void load(WorldLoadEvent e){know(e.getWorld());reload();}
 public static long bytes(long mib){if(mib< -1)throw new IllegalArgumentException("Cache limit must be -1 or nonnegative MiB");return mib==-1?-1:Math.multiplyExact(mib,1048576L);}
 public void reload(){
  global=bytes(plugin.getConfig().getLong("cache.global.max-mib",plugin.getConfig().getLong("lod.cache-mib",4096)));
  var snapshot=new HashMap<UUID,CacheRetention.Rule>();var by=plugin.getConfig().getConfigurationSection("cache.by_world");
  if(by!=null)for(var e:by.getValues(false).entrySet())if(e.getValue() instanceof ConfigurationSection cfg){
   for(String id:worlds.stringPropertyNames())if(e.getKey().equals(id)||e.getKey().equals(worlds.getProperty(id))){
    snapshot.put(UUID.fromString(id),new CacheRetention.Rule(cfg.contains("max-mib")?bytes(cfg.getLong("max-mib")):null,cfg.getInt("priority",0)));
   }
  }rules=Map.copyOf(snapshot);lastRun=0;
 }
 public synchronized CompletableFuture<String> trim(){
  if(running)return current;running=true;lastRun=System.currentTimeMillis();
  var result=new CompletableFuture<String>();current=result;long limit=global;var policy=rules;
  worker.execute(()->{try{
   var entries=new ArrayList<>(plugin.getVoxyService().cache().entries());entries.addAll(plugin.getDhSupport().getLodRepository().cacheEntriesAsync().join());
   var victims=CacheRetention.victims(entries,limit,policy);
   for(var entry:victims)if(entry.id().startsWith("voxy:"))plugin.getVoxyService().cache().evict(entry);
   plugin.getDhSupport().getLodRepository().evictAsync(victims).join();
   long total=entries.stream().mapToLong(CacheRetention.Entry::bytes).sum()-victims.stream().mapToLong(CacheRetention.Entry::bytes).sum();
   status="cache bytes="+total+", evicted="+victims.size()+", limit="+limit+(limit>=0&&total>limit?" (protected worlds exceed global limit)":"");result.complete(status);
  }catch(Exception e){status="cache maintenance failed: "+e;plugin.getLogger().warning(status);result.completeExceptionally(e);}finally{running=false;}});return result;
 }
 public boolean command(CommandSender sender,String[] args){
  if(args.length==0||args[0].equals("status")){sender.sendMessage(status);return true;}
  if(args[0].equals("trim")){trim().whenComplete((s,e)->Bukkit.getScheduler().runTask(plugin,()->sender.sendMessage(e==null?s:e.toString())));return true;}
  sender.sendMessage("/dhs cache status|trim");return true;
 }
 public void close(){worker.shutdownNow();HandlerList.unregisterAll(this);}
}
