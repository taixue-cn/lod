package cc.taixue.lod.core;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ColumnCacheTest {
    @TempDir Path dir;
    @Test void persistsButNeverCrossesWorlds() throws Exception {
        var a=new ColumnCache.Key(UUID.randomUUID(),-8,11);var b=new ColumnCache.Key(UUID.randomUUID(),-8,11);
        var cache=new ColumnCache(dir,Duration.ofMinutes(5),10000);
        cache.put(a,new ColumnCache.Entry(123,new byte[]{1,2,3}));
        assertNull(cache.get(b));assertArrayEquals(new byte[]{1,2,3},new ColumnCache(dir,Duration.ofMinutes(5),10000).get(a).body());
        cache.invalidate(a);assertNull(cache.get(a));
    }
    @Test void corruptEntriesAreDiscardedAndSizeLimitEvicts() throws Exception {
        var k=new ColumnCache.Key(UUID.randomUUID(),0,0);var c=new ColumnCache(dir,Duration.ofMinutes(5),0);
        c.put(k,new ColumnCache.Entry(1,new byte[]{7}));c.trim();assertNull(c.get(k));
        c.put(k,new ColumnCache.Entry(1,new byte[]{7}));Files.write(dir.resolve(k.world().toString()).resolve("0_0.gz"),new byte[]{1});assertNull(c.get(k));
    }
    @Test void unlimitedPersistentCacheSurvivesAgeAndTrim() throws Exception {
        var key=new ColumnCache.Key(UUID.randomUUID(),2,-3);var cache=new ColumnCache(dir,Duration.ofSeconds(-1),-1);
        cache.put(key,new ColumnCache.Entry(11,new byte[]{4,5}));Files.setLastModifiedTime(dir.resolve(key.world().toString()).resolve("2_-3.gz"),java.nio.file.attribute.FileTime.fromMillis(1));cache.trim();
        assertArrayEquals(new byte[]{4,5},new ColumnCache(dir,Duration.ofSeconds(-1),-1).get(key).body());
        var entry=cache.entries().getFirst();cache.evict(entry);assertNull(cache.get(key));
    }
}
