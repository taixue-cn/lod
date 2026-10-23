package cc.taixue.lod.core;
import dev.vox.lss.common.wire.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ColumnValidationTest {
    private byte[] column(int sy,int paletteValue,int index) {
        int[] values=new int[4096];values[0]=index;
        var block=new WireSectionCursor.WireContainer(4,new int[]{paletteValue},WireSectionCursor.pack(values,4));
        var biome=new WireSectionCursor.WireContainer(0,new int[]{1},new long[0]);
        return WireSectionCursor.emit(new WireSectionCursor.WireColumn(List.of("minecraft:stone","minecraft:plains"),List.of(new WireSectionCursor.WireSection(sy,4096,0,block,biome,null,null))),WireSectionCursor.Layout.V20);
    }
    @Test void validatesWorldHeightAndActualPackedIndices(){
        assertEquals(1,ColumnValidation.validate(column(0,0,0),-4,20).sections().size());
        assertThrows(IllegalArgumentException.class,()->ColumnValidation.validate(column(20,0,0),-4,20));
        assertThrows(IllegalArgumentException.class,()->ColumnValidation.validate(column(0,0,1),-4,20));
    }
}
