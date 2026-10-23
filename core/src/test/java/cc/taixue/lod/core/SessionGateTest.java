package cc.taixue.lod.core;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class SessionGateTest {
    @Test void worldRevocationAndReconnectRetireEveryPriorLease() {
        var g=new SessionGate();var p=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
        var first=g.observe(p,a,true);assertTrue(g.valid(first));
        assertNull(g.observe(p,a,false));assertFalse(g.valid(first));
        var granted=g.observe(p,a,true);assertFalse(g.valid(first));
        var changed=g.observe(p,b,true);assertFalse(g.valid(granted));assertTrue(g.valid(changed));
        g.observe(p,a,true);assertFalse(g.valid(first));
        g.remove(p);g.observe(p,a,true);assertFalse(g.valid(first));
    }
    @Test void playersNeverShareLeases() {
        var g=new SessionGate();var w=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
        var l=g.observe(a,w,true);g.observe(b,w,false);assertTrue(g.valid(l));
    }
}
