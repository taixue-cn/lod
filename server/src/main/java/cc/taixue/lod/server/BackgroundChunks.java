package cc.taixue.lod.server;
import java.util.*;
import java.util.concurrent.*;
/** Prevent cache work's own transient chunk loads/unloads from recursively starting more work. */
public final class BackgroundChunks {
 private record Key(UUID world,int x,int z){}
 private static final Map<Key,Long> loaded=new ConcurrentHashMap<>();
 public static void loaded(UUID world,int x,int z){loaded.put(new Key(world,x,z),System.currentTimeMillis());}
 public static boolean unloaded(UUID world,int x,int z){Long stamp=loaded.remove(new Key(world,x,z));return stamp!=null&&System.currentTimeMillis()-stamp<600000;}
 public static void changed(UUID world,int x,int z){loaded.remove(new Key(world,x,z));}
 public static void clear(){loaded.clear();}
}
