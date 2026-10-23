package no.jckf.dhsupport.bukkit;
import cc.taixue.lod.server.*;
import no.jckf.dhsupport.bukkit.handler.*;
import no.jckf.dhsupport.bukkit.commands.*;
import no.jckf.dhsupport.core.DhSupport;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** ARCH integration of upstream DH Support; bundled provenance retains its GPL license. */
public final class DhSupportBukkitPlugin extends JavaPlugin {
    private DhSupport dhSupport;
    private BukkitScheduler scheduler;
    private ConfigLoader configLoader;
    private PluginMessageProxy proxy;
    private WorldAccess access;
    private VoxyService voxy;
    private WorldEditInvalidation worldEdit;
    private CacheManager cacheManager;
    private WarmService warmService;
    public void onEnable() {
        saveDefaultConfig();
        dhSupport=new DhSupport(getPluginMeta().getVersion(),getServer().getMinecraftVersion());
        dhSupport.setLogger(getLogger());dhSupport.setDataDirectory(getDataFolder().getAbsolutePath());
        loadDhsConfig();access=new WorldAccess(this);
        DhGuard.install(access,dhSupport.getPluginMessageHandler());
        scheduler=new BukkitScheduler(this);dhSupport.setScheduler(scheduler);dhSupport.onEnable();
        getServer().getPluginManager().registerEvents(new WorldHandler(this),this);
        getServer().getPluginManager().registerEvents(new PlayerHandler(this),this);
        proxy=new PluginMessageProxy(this);proxy.onEnable();
        voxy=new VoxyService(this,access);voxy.start();
        cacheManager=new CacheManager(this);warmService=new WarmService(this);
        if(getServer().getPluginManager().getPlugin("WorldEdit")!=null||getServer().getPluginManager().getPlugin("FastAsyncWorldEdit")!=null) {
            try{worldEdit=new WorldEditInvalidation(this,voxy,dhSupport);}catch(LinkageError|RuntimeException error){getLogger().warning("WorldEdit cache invalidation unavailable: "+error);}
        }
        scheduler.runTimer(()->DhGuard.watch(dhSupport),1,5);
        scheduler.runTimer(()->{dhSupport.getGenerationTracker().prune();dhSupport.updateTouchedLods();},20,Math.max(20,getConfig().getInt("lod_refresh_interval",15)*20L));
        getCommand("dhs").setExecutor(new DhsCommand(this));getCommand("dh").setExecutor(new DhCommand(this));
        getLogger().info("ARCH LOD ready: Voxy LSS v20 + DH v16, world permission enforced, existing terrain only");
    }
    public void onDisable() {
        if(warmService!=null)warmService.close();if(cacheManager!=null)cacheManager.close();if(worldEdit!=null)worldEdit.close();if(voxy!=null)voxy.close();if(proxy!=null)proxy.onDisable();if(access!=null)access.close();DhGuard.clear();
        if(scheduler!=null)scheduler.cancelTasks();if(dhSupport!=null)dhSupport.onDisable();
    }
    public void loadDhsConfig(){
        if(configLoader!=null)configLoader.onDisable();configLoader=new ConfigLoader(this);configLoader.onEnable();
        dhSupport.getConfig().set("generate_new_chunks",false);dhSupport.getConfig().set("check_for_updates",false);
        if(access!=null)access.refreshPolicy();if(cacheManager!=null)cacheManager.reload();if(warmService!=null)warmService.reload();
    }
    public CacheManager getCacheManager(){return cacheManager;}
    public WarmService getWarmService(){return warmService;}
    public World getWorld(String name){for(World w:getServer().getWorlds())if(w.getName().replace(' ','_').equals(name))return w;return null;}
    public DhSupport getDhSupport(){return dhSupport;}
    public VoxyService getVoxyService(){return voxy;}
    public WorldAccess getWorldAccess(){return access;}
}
