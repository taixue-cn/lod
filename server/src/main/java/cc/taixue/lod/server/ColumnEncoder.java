package cc.taixue.lod.server;

import dev.vox.lss.common.wire.*;
import org.bukkit.ChunkSnapshot;
import java.util.*;

/** Encode immutable Paper snapshots directly into LSS's version-neutral v20 body. */
public final class ColumnEncoder {
    private ColumnEncoder() {}
    public static byte[] encode(ChunkSnapshot snapshot,int minY,int maxY) {
        var dictionary=new LinkedHashMap<String,Integer>();var sections=new ArrayList<WireSectionCursor.WireSection>();
        var identities=new HashMap<String,String>();
        for(int sy=Math.floorDiv(minY,16);sy<Math.floorDiv(maxY,16);sy++) {
            int[] blocks=new int[4096],biomes=new int[64];byte[] bl=new byte[2048],sl=new byte[2048];int count=0,fluids=0;
            boolean blockLight=false,skyLight=false;
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int index=(y<<8)|(z<<4)|x,wy=sy*16+y;
                var state=snapshot.getBlockData(x,wy,z);String raw=state.getAsString();
                String identity=identities.computeIfAbsent(raw,ColumnEncoder::canonical);
                blocks[index]=dictionary.computeIfAbsent(identity,k->dictionary.size());
                if(!state.getMaterial().isAir())count++;
                if(state.getMaterial()==org.bukkit.Material.WATER||state.getMaterial()==org.bukkit.Material.LAVA)fluids++;
                int b=snapshot.getBlockEmittedLight(x,wy,z),s=snapshot.getBlockSkyLight(x,wy,z);
                bl[index>>1]|=(byte)(b<<((index&1)*4));sl[index>>1]|=(byte)(s<<((index&1)*4));
                blockLight|=b!=0;skyLight|=s!=0;
            }
            for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++) {
                String name=snapshot.getBiome(x*4,sy*16+y*4,z*4).getKey().toString();
                biomes[(y<<4)|(z<<2)|x]=dictionary.computeIfAbsent(name,k->dictionary.size());
            }
            sections.add(new WireSectionCursor.WireSection(sy,count,fluids,container(blocks,true),container(biomes,false),blockLight?bl:null,skyLight?sl:null));
        }
        return WireSectionCursor.emit(new WireSectionCursor.WireColumn(new ArrayList<>(dictionary.keySet()),sections),WireSectionCursor.Layout.V20);
    }
    private static String canonical(String state) {
        int bracket=state.indexOf('[');if(bracket<0)return state;
        var properties=new TreeMap<String,String>();for(String p:state.substring(bracket+1,state.length()-1).split(",")){String[] kv=p.split("=",2);properties.put(kv[0],kv[1]);}
        return IdentityCodec.canonical(state.substring(0,bracket),properties);
    }
    private static WireSectionCursor.WireContainer container(int[] values,boolean block) {
        var palette=new LinkedHashMap<Integer,Integer>();int[] indices=new int[values.length];
        for(int i=0;i<values.length;i++)indices[i]=palette.computeIfAbsent(values[i],k->palette.size());
        int bits=palette.size()==1?0:Math.max(block?4:1,32-Integer.numberOfLeadingZeros(palette.size()-1));
        return new WireSectionCursor.WireContainer(bits,palette.keySet().stream().mapToInt(i->i).toArray(),bits==0?new long[0]:WireSectionCursor.pack(indices,bits));
    }
}
