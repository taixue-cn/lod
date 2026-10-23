package cc.taixue.lod.core;
import java.util.*;

/** Shared Voxy/DH budget. Lower priority is evicted first, oldest writes first within a priority. */
public final class CacheRetention {
    public record Entry(String id,UUID world,long bytes,long timestamp){}
    // Explicit world -1 pins its entries; absent limits inherit the global rolling budget.
    public record Rule(Long maxBytes,int priority){public Rule{if(maxBytes!=null&&maxBytes< -1)throw new IllegalArgumentException("limit below -1");}}
    public static List<Entry> victims(List<Entry> entries,long global,Map<UUID,Rule> rules){
        if(global< -1)throw new IllegalArgumentException("limit below -1");
        var ordered=new ArrayList<>(entries);ordered.sort(Comparator.<Entry>comparingInt(e->rules.getOrDefault(e.world(),new Rule(null,0)).priority()).thenComparingLong(Entry::timestamp).thenComparing(Entry::id));
        var sizes=new HashMap<UUID,Long>();long total=0;for(var e:entries){sizes.merge(e.world(),e.bytes(),Long::sum);total+=e.bytes();}
        var remove=new ArrayList<Entry>();var removed=new HashSet<String>();
        for(var e:ordered){var r=rules.get(e.world());if(r!=null&&r.maxBytes()!=null&&r.maxBytes()>=0&&sizes.get(e.world())>r.maxBytes()){
            remove.add(e);removed.add(e.id());sizes.merge(e.world(),-e.bytes(),Long::sum);total-=e.bytes();
        }}
        if(global>=0)for(var e:ordered){if(total<=global)break;var r=rules.get(e.world());if(removed.contains(e.id())||(r!=null&&Objects.equals(r.maxBytes(),-1L)))continue;remove.add(e);total-=e.bytes();}
        return remove;
    }
}
