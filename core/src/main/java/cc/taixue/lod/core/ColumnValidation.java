package cc.taixue.lod.core;
import dev.vox.lss.common.wire.*;
import java.util.*;

/** Extra semantic validation for client-originated palettes, beyond wire framing. */
public final class ColumnValidation {
    private ColumnValidation(){}
    public static WireSectionCursor.WireColumn validate(byte[] body,int minimumSection,int maximumSection) {
        if(body.length>Wire.MAX_FRAME)throw new IllegalArgumentException("column too large");
        var column=WireSectionCursor.parse(body,WireSectionCursor.Layout.V20);
        if(column.dictionary().size()>65536||column.sections().size()>maximumSection-minimumSection)throw new IllegalArgumentException("column bounds");
        column.dictionary().forEach(IdentityCodec::validate);
        var seen=new HashSet<Integer>();
        for(var s:column.sections()) {
            if(s.sectionY()<minimumSection||s.sectionY()>=maximumSection||!seen.add(s.sectionY()))throw new IllegalArgumentException("section range/duplicate");
            if(s.nonEmptyBlockCount()<0||s.nonEmptyBlockCount()>4096||s.fluidCount()<0||s.fluidCount()>4096)throw new IllegalArgumentException("section count");
            validateContainer(s.blocks(),4096);validateContainer(s.biomes(),64);
        }
        return column;
    }
    public static int[] values(WireSectionCursor.WireContainer c,int n) {
        if(c.bits()==0){int[] v=new int[n];Arrays.fill(v,c.palette()[0]);return v;}
        int[] indices=WireSectionCursor.unpack(c.data(),c.bits(),n);
        for(int i=0;i<n;i++){if(indices[i]>=c.palette().length)throw new IllegalArgumentException("palette index");indices[i]=c.palette()[indices[i]];}
        return indices;
    }
    private static void validateContainer(WireSectionCursor.WireContainer c,int n){values(c,n);}
}
