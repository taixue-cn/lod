package cc.taixue.lod.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WireTest {
    @Test void negativeCoordinatesAndVarintsRoundTrip() {
        for(int n:new int[]{0,127,128,16384,Integer.MAX_VALUE,-1,Integer.MIN_VALUE}) {
            var r=new Wire.Reader(new Wire.Writer().varInt(n).result());assertEquals(n,r.varInt());r.end();
            assertEquals(n,Wire.x(Wire.pack(n,-97)));assertEquals(-97,Wire.z(Wire.pack(n,-97)));
        }
    }
    @Test void hostileLengthsAndTrailingBytesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new Wire.Reader(new byte[]{-1,-1,-1,-1,127}).varInt());
        assertThrows(IllegalArgumentException.class,()->new Wire.Reader(new byte[]{127}).bytes(32));
        assertThrows(IllegalArgumentException.class,()->new Wire.Reader(new byte[]{2}).bool());
        assertThrows(IllegalArgumentException.class,()->new Wire.Reader(new byte[]{1}).end());
        assertThrows(IllegalArgumentException.class,()->new Wire.Reader(new byte[]{5,1}).utf(1024));
    }
}
