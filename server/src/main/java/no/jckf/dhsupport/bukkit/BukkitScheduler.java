package no.jckf.dhsupport.bukkit;

import cc.taixue.lod.server.LodServer;
import no.jckf.dhsupport.core.scheduling.Scheduler;
import org.bukkit.Bukkit;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Paper-only scheduler; world/snapshot access always executes on the main thread. */
public final class BukkitScheduler implements Scheduler {
    private final LodServer plugin;
    private final ExecutorService workers;
    private final ScheduledExecutorService timers;
    public BukkitScheduler(LodServer plugin) {
        this.plugin=plugin;
        int n=Math.clamp(plugin.getConfig().getInt("scheduler_threads",2),1,4);
        workers=new ThreadPoolExecutor(n,n,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"ArchLOD-DH");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        timers=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"ArchLOD-timer");t.setDaemon(true);return t;});
    }
    public boolean canReadWorldAsync(){return false;}
    private static <U> void complete(CompletableFuture<U> f,Supplier<U> s){try{f.complete(s.get());}catch(Throwable e){f.completeExceptionally(e);}}
    public <U> CompletableFuture<U> runOnMainThread(Supplier<U> s){
        var f=new CompletableFuture<U>();
        if(!plugin.isEnabled()){f.completeExceptionally(new CancellationException("plugin stopped"));return f;}
        if(Bukkit.isPrimaryThread())complete(f,s);else Bukkit.getScheduler().runTask(plugin,()->complete(f,s));return f;
    }
    public <U> CompletableFuture<U> runOnMainThreadDelayed(Supplier<U> s,long ticks){var f=new CompletableFuture<U>();Bukkit.getScheduler().runTaskLater(plugin,()->complete(f,s),Math.max(1,ticks));return f;}
    public <U> CompletableFuture<U> runOnMainThreadDelayed(Supplier<U> s,long n,TimeUnit u){return runOnMainThreadDelayed(s,Math.max(1,u.toMillis(n)/50));}
    public <U> CompletableFuture<U> runOnRegionThread(UUID w,int x,int z,Supplier<U> s){return runOnMainThread(s);}
    public <U> CompletableFuture<U> runOnRegionThreadDelayed(UUID w,int x,int z,Supplier<U> s,long n){return runOnMainThreadDelayed(s,n);}
    public <U> CompletableFuture<U> runOnRegionThreadDelayed(UUID w,int x,int z,Supplier<U> s,long n,TimeUnit u){return runOnMainThreadDelayed(s,n,u);}
    public <U> CompletableFuture<U> runOnSeparateThread(Supplier<U> s){var f=new CompletableFuture<U>();try{workers.execute(()->complete(f,s));}catch(RejectedExecutionException e){f.completeExceptionally(e);}return f;}
    public <U> CompletableFuture<U> runOnSeparateThreadDelayed(Supplier<U> s,long n){return runOnSeparateThreadDelayed(s,n*50,TimeUnit.MILLISECONDS);}
    public <U> CompletableFuture<U> runOnSeparateThreadDelayed(Supplier<U> s,long n,TimeUnit u){var f=new CompletableFuture<U>();try{timers.schedule(()->runOnSeparateThread(s).whenComplete((v,e)->{if(e==null)f.complete(v);else f.completeExceptionally(e);}),n,u);}catch(RejectedExecutionException e){f.completeExceptionally(e);}return f;}
    public ExecutorService getExecutor(){return workers;}
    public void runTimer(Runnable r,long delay,long interval){Bukkit.getScheduler().runTaskTimer(plugin,r,delay,interval);}
    public void cancelTasks(){timers.shutdownNow();workers.shutdownNow();Bukkit.getScheduler().cancelTasks(plugin);}
}
