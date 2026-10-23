package cc.taixue.lod.server;
import cc.taixue.lod.core.SavedChunks;
import no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Server-owned terrain generation; player presence and client uploads are unnecessary. */
public final class WarmService implements AutoCloseable,Listener {
 private final DhSupportBukkitPlugin plugin;
 private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"LOD-region-index");t.setDaemon(true);return t;});
 private final Map<UUID,Job> jobs=new LinkedHashMap<>();
 private final LinkedHashMap<Key,ChunkSnapshot> unloads=new LinkedHashMap<>();
 private final Set<Key> unloadDh=new LinkedHashSet<>();
 private int active,rotation;private boolean closed;private long completed,failed;
 private record Key(UUID world,int x,int z){}
 private static final class Job {
  final World world;final Set<String> protocols;final Path region;SavedChunks scan;boolean pending,finished;long done,errors;
  int regionX=Integer.MIN_VALUE,regionZ;final Set<Long> sections=new HashSet<>();
  Job(World world,Set<String> protocols,Path region){this.world=world;this.protocols=protocols;this.region=region;}
 }
 public WarmService(DhSupportBukkitPlugin plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
  Bukkit.getScheduler().runTaskLater(plugin,()->Bukkit.getWorlds().forEach(this::auto),40);
 }
 private ConfigurationSection settings(World world){var by=plugin.getConfig().getConfigurationSection("prewarm.by_world");if(by==null)return null;Object value=by.getValues(false).get(world.getName());if(value==null)value=by.getValues(false).get(world.getUID().toString());return value instanceof ConfigurationSection s?s:null;}
 private Set<String> protocols(World world){var s=settings(world);var list=s!=null&&s.contains("protocols")?s.getStringList("protocols"):plugin.getConfig().getStringList("prewarm.global.protocols");if(list.isEmpty())list=List.of("voxy","dh");var out=new HashSet<>(list);if(!Set.of("voxy","dh").containsAll(out))throw new IllegalArgumentException("prewarm protocols: voxy, dh");return Set.copyOf(out);}
 private void auto(World world){var s=settings(world);boolean enabled=s!=null&&s.contains("enabled")?s.getBoolean("enabled"):plugin.getConfig().getBoolean("prewarm.global.enabled",false);if(enabled)start(world);}
 @EventHandler public void load(WorldLoadEvent e){Bukkit.getScheduler().runTaskLater(plugin,()->auto(e.getWorld()),20);}
 @EventHandler public void unloadWorld(WorldUnloadEvent e){jobs.remove(e.getWorld().getUID());unloads.keySet().removeIf(k->k.world.equals(e.getWorld().getUID()));unloadDh.removeIf(k->k.world.equals(e.getWorld().getUID()));}
 public void reload(){Bukkit.getWorlds().forEach(this::auto);}
 public boolean finished(UUID world){Job job=jobs.get(world);return job!=null&&job.finished;}
 public boolean idle(){return active==0&&unloads.isEmpty()&&unloadDh.isEmpty();}
 public boolean start(World world){if(!plugin.getWorldAccess().cacheAllowed(world.getUID()))return false;Job old=jobs.get(world.getUID());if(old!=null&&!old.finished)return false;var job=new Job(world,protocols(world),region(world));jobs.put(world.getUID(),job);return true;}
 private Path region(World world){Path root=world.getWorldFolder().toPath();if(Files.isDirectory(root.resolve("region")))return root.resolve("region");return root.resolve(world.getEnvironment()==World.Environment.NETHER?"DIM-1":"DIM1").resolve("region");}
 private void main(Runnable r){if(!closed&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{if(!closed)r.run();});}
 private void tick(){
  if(closed)return;int budget=Math.clamp(plugin.getConfig().getInt("prewarm.global.chunks-per-tick",1),1,8);
  while(budget>0&&active<2&&!unloads.isEmpty()){
   var it=unloads.entrySet().iterator();var e=it.next();Key key=e.getKey();ChunkSnapshot snapshot=e.getValue();it.remove();World world=Bukkit.getWorld(key.world);if(world==null||!plugin.getWorldAccess().cacheAllowed(key.world))continue;
   active++;budget--;var future=snapshot==null?plugin.getVoxyService().warm(world,key.x,key.z):plugin.getVoxyService().refreshSnapshot(world,snapshot);
   future.whenComplete((ok,error)->main(()->{active--;if(error!=null){failed++;plugin.getLogger().warning("Unload Voxy refresh: "+error);}else completed++;}));
  }
  if(budget>0&&active<2&&!unloadDh.isEmpty()){
   var it=unloadDh.iterator();Key key=it.next();it.remove();if(plugin.getWorldAccess().cacheAllowed(key.world)){
    active++;budget--;plugin.getDhSupport().refreshLod(key.world,key.x,key.z).whenComplete((model,error)->main(()->{active--;if(error!=null)failed++;else completed++;}));
   }
  }
  var order=new ArrayList<>(jobs.values());if(!order.isEmpty())Collections.rotate(order,-(rotation++%order.size()));
  for(Job job:order){
   if(active>=2||budget<=0)break;if(job.pending||job.finished)continue;
   if(!plugin.getWorldAccess().cacheAllowed(job.world.getUID())){job.finished=true;continue;}
   job.pending=true;active++;budget--;reader.execute(()->{
    try{if(job.scan==null)job.scan=new SavedChunks(job.region);var next=job.scan.next();main(()->dispatch(job,next));}
    catch(Exception e){main(()->{job.pending=false;job.finished=true;job.errors++;active--;plugin.getLogger().warning("Prewarm scan "+job.world.getName()+": "+e);});}
   });
  }
 }
 private void dispatch(Job job,SavedChunks.Chunk next){
  if(jobs.get(job.world.getUID())!=job||!plugin.getWorldAccess().cacheAllowed(job.world.getUID())){active--;job.pending=false;job.finished=true;return;}
  if(next==null){active--;job.pending=false;job.finished=true;plugin.getLogger().info("Prewarm complete "+job.world.getName()+": chunks="+job.done+", failures="+job.errors);return;}
  if(!job.world.isChunkGenerated(next.x(),next.z())){active--;job.pending=false;return;}
  var tasks=new ArrayList<CompletableFuture<?>>();if(job.protocols.contains("voxy"))tasks.add(plugin.getVoxyService().warm(job.world,next.x(),next.z()));
  if(job.regionX!=next.regionX()||job.regionZ!=next.regionZ()){job.sections.clear();job.regionX=next.regionX();job.regionZ=next.regionZ();}
  int sx=Math.floorDiv(next.x(),4),sz=Math.floorDiv(next.z(),4);long key=((long)sx<<32)|(sz&0xffffffffL);
  if(job.protocols.contains("dh")&&job.sections.add(key))tasks.add(plugin.getDhSupport().refreshLod(job.world.getUID(),sx,sz));
  CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->main(()->{active--;job.pending=false;job.done++;if(error!=null){job.errors++;plugin.getLogger().warning("Prewarm "+job.world.getName()+": "+error);}}));
 }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void unload(ChunkUnloadEvent event){
  var c=event.getChunk();var world=c.getWorld();if(BackgroundChunks.unloaded(world.getUID(),c.getX(),c.getZ()))return;
  if(!plugin.getConfig().getBoolean("cache.refresh-on-unload",true)||!plugin.getWorldAccess().cacheAllowed(world.getUID()))return;
  // Beyond the snapshot cap, retain coordinates and read the saved chunk after unload.
  Key key=new Key(world.getUID(),c.getX(),c.getZ());unloads.put(key,unloads.size()<256?c.getChunkSnapshot(true,true,false):null);
  unloadDh.add(new Key(world.getUID(),Math.floorDiv(c.getX(),4),Math.floorDiv(c.getZ(),4)));
 }
 public boolean command(CommandSender sender,String[] args){
  String action=args.length==0?"status":args[0];String target=args.length>1?args[1]:"all";
  if(action.equals("status")){sender.sendMessage("prewarm active="+active+", unload queue="+unloads.size()+"/"+unloadDh.size()+", refreshed="+completed+", failed="+failed);for(var job:jobs.values())sender.sendMessage(job.world.getName()+": "+(job.finished?"finished":"running")+", chunks="+job.done+", failures="+job.errors);return true;}
  if(!Set.of("start","stop").contains(action)){sender.sendMessage("/lod warm start|stop|status [world|all]");return true;}
  int changed=0;for(World world:Bukkit.getWorlds())if(target.equals("all")||target.equals(world.getName())||target.equals(world.getUID().toString())){if(action.equals("start")){if(start(world))changed++;}else if(jobs.remove(world.getUID())!=null)changed++;}
  sender.sendMessage("Prewarm "+action+": "+changed+" worlds");return true;
 }
 public void close(){closed=true;reader.shutdownNow();jobs.clear();unloads.clear();unloadDh.clear();BackgroundChunks.clear();HandlerList.unregisterAll(this);}
}
