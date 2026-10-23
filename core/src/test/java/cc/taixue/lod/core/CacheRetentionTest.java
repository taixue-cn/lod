package cc.taixue.lod.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CacheRetentionTest {
 @Test void combinedBudgetsPriorityAgeAndPinnedWorlds(){
  UUID a=UUID.randomUUID(),b=UUID.randomUUID(),p=UUID.randomUUID();
  var old=new CacheRetention.Entry("voxy:a",a,30,1);var newer=new CacheRetention.Entry("dh:a",a,30,2);var high=new CacheRetention.Entry("dh:b",b,60,0);var pinned=new CacheRetention.Entry("voxy:p",p,90,0);
  var all=List.of(old,newer,high,pinned);var rules=Map.of(a,new CacheRetention.Rule(40L,0),b,new CacheRetention.Rule(null,10),p,new CacheRetention.Rule(-1L,-100));
  assertEquals(List.of(old),CacheRetention.victims(all,-1,rules));
  assertEquals(List.of(old,newer),CacheRetention.victims(all,150,rules));
  assertEquals(List.of(old,newer,high),CacheRetention.victims(all,0,rules));
  assertTrue(CacheRetention.victims(all,-1,Map.of()).isEmpty());
  assertThrows(IllegalArgumentException.class,()->CacheRetention.victims(all,-2,rules));
 }
}
