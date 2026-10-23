package cc.taixue.lod.fixture;
import cc.taixue.lod.core.*;
import cc.taixue.lod.server.*;
import no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Paper/SQLite/filesystem checks, never packaged in the release. */
public final class CacheFixture implements Listener {
 private final JavaPlugin fixture;private final DhSupportBukkitPlugin lod;
 private final List<String> passed=new ArrayList<>();private final Set<UUID> target=new HashSet<>();private final AtomicInteger generated=new AtomicInteger(),unloaded=new AtomicInteger();
 CacheFixture(JavaPlugin fixture,DhSupportBukkitPlugin lod){this.fixture=fixture;this.lod=lod;}
 private <T>T main(Supplier<T> operation)throws Exception{var f=new CompletableFuture<T>();Bukkit.getScheduler().runTask(fixture,()->{try{f.complete(operation.get());}catch(Throwable e){f.completeExceptionally(e);}});return f.get(60,TimeUnit.SECONDS);}
 private void check(boolean value,String name){if(!value)throw new AssertionError(name);passed.add(name);fixture.getLogger().info("CACHE_PASS "+name);}
 private void eventually(Callable<Boolean> condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);while(System.nanoTime()<deadline){if(condition.call())return;Thread.sleep(100);}throw new AssertionError("condition timed out");}
 private List<CacheRetention.Entry> entries()throws Exception{var all=new ArrayList<>(lod.getVoxyService().cache().entries());all.addAll(lod.getDhSupport().getLodRepository().cacheEntriesAsync().get(30,TimeUnit.SECONDS));return all;}
 private List<CacheRetention.Entry> entries(UUID world)throws Exception{return entries().stream().filter(e->e.world().equals(world)).toList();}
 private void config(long global,Map<String,Object> per)throws Exception{main(()->{lod.getConfig().set("cache.global.max-mib",global);lod.getConfig().set("cache.by_world",null);for(var e:per.entrySet())lod.getConfig().createSection("cache.by_world."+e.getKey(),(Map<?,?>)e.getValue());lod.getCacheManager().reload();return true;});}
 private void trim()throws Exception{main(()->lod.getCacheManager().trim()).get(30,TimeUnit.SECONDS);}
 private void populate(World world)throws Exception{main(()->{
  var a=lod.getVoxyService().warm(world,4,4);var b=lod.getDhSupport().refreshLod(world.getUID(),1,1);return CompletableFuture.allOf(a,b);
 }).get(30,TimeUnit.SECONDS);}
 private String mapping(byte[] data)throws Exception{
  var b=java.nio.ByteBuffer.wrap(data);b.position(12);for(int i=0;i<7;i++){int n=b.getInt();b.position(b.position()+n);}byte[] compressed=new byte[b.getInt()];b.get(compressed);
  try(var in=(java.io.InputStream)Class.forName("cc.taixue.lod.lib.xz.XZInputStream").getConstructor(java.io.InputStream.class).newInstance(new java.io.ByteArrayInputStream(compressed))){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);}
 }
 @EventHandler public void generated(ChunkPopulateEvent event){if(target.contains(event.getWorld().getUID()))generated.incrementAndGet();}
 @EventHandler public void unloaded(org.bukkit.event.world.ChunkUnloadEvent event){if(target.contains(event.getWorld().getUID())&&event.getChunk().getX()==4&&event.getChunk().getZ()==4)unloaded.incrementAndGet();}
 public void start(){Bukkit.getPluginManager().registerEvents(this,fixture);var thread=new Thread(()->{
  try{run();write("PASS","");}catch(Throwable e){fixture.getLogger().warning("CACHE_FAIL "+e);e.printStackTrace();write("FAIL",e.toString());}finally{HandlerList.unregisterAll(this);}
 },"LOD-cache-fixture");thread.setDaemon(true);thread.start();}
 private static final class TinyGenerator extends org.bukkit.generator.ChunkGenerator {
  @Override public Location getFixedSpawnLocation(World world,Random random){return new Location(world,0,64,0);}
  @Override public ChunkData generateChunkData(World world,Random random,int x,int z,BiomeGrid biomes){var data=createChunkData(world);data.setBlock(0,63,0,Material.STONE);return data;}
 }
 private void run()throws Exception{
  World a=main(()->new WorldCreator("cache-a").keepSpawnInMemory(false).generator(new TinyGenerator()).createWorld());
  World b=main(()->new WorldCreator("cache-b").keepSpawnInMemory(false).generator(new TinyGenerator()).createWorld());
  main(()->{lod.getConfig().set("cache.refresh-on-unload",false);lod.getConfig().set("cache.maintenance-seconds",3600);lod.getConfig().set("lod.worlds.mode","blacklist");lod.getConfig().set("lod.worlds.entries",List.of());lod.getWorldAccess().refreshPolicy();
   for(World w:List.of(a,b)){w.setGameRule(GameRule.SPAWN_CHUNK_RADIUS,0);w.setKeepSpawnInMemory(false);w.getBlockAt(64,64,64).setType(Material.GOLD_BLOCK);w.save();target.add(w.getUID());}return true;});
  config(-1,Map.of());populate(a);populate(b);
  check(entries(a.getUID()).stream().anyMatch(e->e.id().startsWith("dh:"))&&entries(a.getUID()).stream().anyMatch(e->e.id().startsWith("voxy:")),"server builds both model formats, including partial sections");
  config(-1,Map.of("cache-a",Map.of("max-mib",0),"cache-b",Map.of("max-mib",-1)));trim();
  check(entries(a.getUID()).isEmpty()&&entries(b.getUID()).size()>=2,"per-world limit removes Voxy and DH while explicit -1 protects another world");
  config(0,Map.of("cache-b",Map.of("max-mib",-1)));trim();check(entries(b.getUID()).size()>=2,"pinned world survives global zero budget");
  config(-1,Map.of("cache-a",Map.of("max-mib",0),"cache-b",Map.of("max-mib",0)));trim();
  byte[] bytes=new byte[700000];new Random(1234).nextBytes(bytes);
  for(World world:List.of(a,b)){lod.getVoxyService().cache().put(new ColumnCache.Key(world.getUID(),1000,1000),new ColumnCache.Entry(System.currentTimeMillis(),bytes));lod.getDhSupport().getLodRepository().saveLodAsync(world.getUID(),250,250,bytes,new byte[0],1).get(30,TimeUnit.SECONDS);}
  config(2,Map.of("cache-a",Map.of("priority",-10),"cache-b",Map.of("priority",10)));trim();
  check(entries(a.getUID()).isEmpty()&&entries(b.getUID()).size()==2&&entries().stream().mapToLong(CacheRetention.Entry::bytes).sum()<=2097152,"global combined budget evicts lower priority world before higher priority");
  config(-1,Map.of("cache-b",Map.of("max-mib",1)));trim();check(entries(b.getUID()).stream().mapToLong(CacheRetention.Entry::bytes).sum()<=1048576,"world-only rolling limit applies when global is unlimited");
  config(-1,Map.of("cache-a",Map.of("max-mib",0),"cache-b",Map.of("max-mib",0)));trim();config(-1,Map.of());
  main(()->{lod.getWarmService().start(a);lod.getConfig().set("prewarm.global.enabled",false);lod.getConfig().set("prewarm.by_world.cache-b.enabled",true);lod.getConfig().set("prewarm.by_world.cache-b.protocols",List.of("dh"));lod.getWarmService().reload();return true;});
  eventually(()->main(()->lod.getWarmService().finished(a.getUID())&&lod.getWarmService().finished(b.getUID())));
  check(entries(a.getUID()).stream().anyMatch(e->e.id().startsWith("voxy:"))&&entries(a.getUID()).stream().anyMatch(e->e.id().startsWith("dh:")),"map-file prewarm fills both formats without players visiting world");
  check(!entries(b.getUID()).isEmpty()&&entries(b.getUID()).stream().allMatch(e->e.id().startsWith("dh:")),"by-world prewarm overrides global disabled and protocol selection");
  check(generated.get()==0,"prewarm creates no new terrain chunks");
  boolean inherited=main(()->{lod.getConfig().set("prewarm.by_world.cache-b.enabled",null);lod.getConfig().set("prewarm.global.enabled",true);lod.getConfig().set("prewarm.by_world.world.enabled",false);lod.getConfig().set("prewarm.by_world.lod-secret.enabled",false);lod.getConfig().set("prewarm.by_world.cache-a.enabled",false);lod.getWarmService().reload();return lod.getWarmService().finished(a.getUID())&&!lod.getWarmService().finished(b.getUID());});
  check(inherited,"global prewarm enables inherited worlds while explicit world disable wins");eventually(()->main(()->lod.getWarmService().finished(b.getUID())));
  int unloadBefore=unloaded.get();
  main(()->{lod.getConfig().set("cache.refresh-on-unload",true);a.getBlockAt(64,64,64).setType(Material.EMERALD_BLOCK);BackgroundChunks.clear();a.unloadChunkRequest(4,4);return true;});
  eventually(()->main(()->!a.isChunkLoaded(4,4)||a.unloadChunk(4,4,true)));
  eventually(()->{var hit=lod.getVoxyService().cache().get(new ColumnCache.Key(a.getUID(),4,4));var dh=lod.getDhSupport().getLodRepository().loadLodAsync(a.getUID(),1,1).get(30,TimeUnit.SECONDS);return hit!=null&&new String(hit.body(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("minecraft:emerald_block")&&dh!=null&&mapping(dh.getData()).contains("minecraft:emerald_block");});
  check(unloaded.get()>unloadBefore,"real chunk unload refreshes both Voxy and DH to the latest block state");
  eventually(()->main(()->lod.getWarmService().idle()));long before=entries(a.getUID()).size();Thread.sleep(1500);check(main(()->lod.getWarmService().idle())&&entries(a.getUID()).size()==before,"background unloads do not recursively rewarm chunks");
 }
 private void write(String status,String error){try{String checks=passed.stream().map(x->"\""+x+"\"").collect(java.util.stream.Collectors.joining(","));Files.writeString(Path.of("cache-test.json"),"{\"status\":\""+status+"\",\"checks\":["+checks+"],\"error\":\""+error.replace("\\","/").replace("\"","'").replace("\n"," ")+"\",\"new_terrain_chunks\":"+generated.get()+"}");}catch(Exception e){throw new RuntimeException(e);}}
}
