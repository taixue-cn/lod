package cc.taixue.lod.client;

import dev.vox.lss.common.wire.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.lang.reflect.*;
import java.util.*;

/** Reads only the active world's level-zero cache; absent sections remain explicitly absent. */
final class VoxyCacheReader {
    private VoxyCacheReader(){}
    static byte[] read(ClientLevel level,int cx,int cz) throws ReflectiveOperationException {
        Class<?> wi=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        Object identity=wi.getMethod("of",net.minecraft.world.level.Level.class).invoke(null,level);
        if(identity==null)return null;
        Object engine=wi.getMethod("getNullable").invoke(identity);if(engine==null)return null;
        Class<?> ec=engine.getClass();
        if(!(boolean)ec.getMethod("isLive").invoke(engine))return null;
        ec.getMethod("acquireRef").invoke(engine);
        try {
            Object mapper=ec.getMethod("getMapper").invoke(engine);Class<?> mc=mapper.getClass();
            Method acquire=ec.getMethod("acquireIfExists",int.class,int.class,int.class,int.class);
            Method blockFor=mc.getMethod("getBlockStateFromBlockId",int.class);
            Object[] biomes=(Object[])mc.getMethod("getBiomeEntries").invoke(mapper);
            var dictionary=new LinkedHashMap<String,Integer>();var sections=new ArrayList<WireSectionCursor.WireSection>();
            var stateMemo=new HashMap<Integer,String>();
            for(int sy=level.getMinSectionY();sy<=level.getMaxSectionY();sy++) {
                Object section=acquire.invoke(engine,0,Math.floorDiv(cx,2),Math.floorDiv(sy,2),Math.floorDiv(cz,2));
                if(section==null)continue;
                long[] data;
                try{data=(long[])section.getClass().getMethod("copyData").invoke(section);}
                finally{section.getClass().getMethod("release").invoke(section);}
                int[] blocks=new int[4096],bio=new int[64];byte[] bl=new byte[2048],sl=new byte[2048];int nonAir=0,fluids=0;
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                    int idx=(y<<8)|(z<<4)|x;
                    int vx=Math.floorMod(cx,2)*16+x,vy=Math.floorMod(sy,2)*16+y,vz=Math.floorMod(cz,2)*16+z;
                    long voxel=data[(vy<<10)|(vz<<5)|vx];int blockId=(int)((voxel>>>27)&0xfffff);int light=(int)(voxel>>>56);
                    String state=stateMemo.get(blockId);
                    if(state==null){state=canonical((BlockState)blockFor.invoke(mapper,blockId));stateMemo.put(blockId,state);}
                    blocks[idx]=dictionary.computeIfAbsent(state,k->dictionary.size());
                    if(!state.startsWith("minecraft:air")&&!state.startsWith("minecraft:cave_air")&&!state.startsWith("minecraft:void_air"))nonAir++;
                    if(state.startsWith("minecraft:water[")||state.startsWith("minecraft:lava["))fluids++;
                    bl[idx>>1]|=(byte)((light&15)<<((idx&1)*4));sl[idx>>1]|=(byte)((light>>>4)<<((idx&1)*4));
                    if((x&3)==0&&(y&3)==0&&(z&3)==0) {
                        int biomeId=(int)((voxel>>>47)&511);String name="minecraft:plains";
                        if(biomeId<biomes.length)name=(String)biomes[biomeId].getClass().getField("biome").get(biomes[biomeId]);
                        bio[((y>>2)<<4)|((z>>2)<<2)|(x>>2)]=dictionary.computeIfAbsent(name,k->dictionary.size());
                    }
                }
                sections.add(new WireSectionCursor.WireSection(sy,nonAir,fluids,container(blocks,true),container(bio,false),bl,sl));
            }
            if(sections.isEmpty())return null;
            return WireSectionCursor.emit(new WireSectionCursor.WireColumn(new ArrayList<>(dictionary.keySet()),sections),WireSectionCursor.Layout.V20);
        }finally{ec.getMethod("releaseRef").invoke(engine);}
    }
    private static String canonical(BlockState state) {
        var props=new TreeMap<String,String>();for(var entry:state.getValues().entrySet())props.put(entry.getKey().getName(),valueName(entry.getKey(),entry.getValue()));
        return IdentityCodec.canonical(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),props);
    }
    @SuppressWarnings({"unchecked","rawtypes"})private static String valueName(Property p,Comparable value){return p.getName(value);}
    private static WireSectionCursor.WireContainer container(int[] values,boolean block) {
        var palette=new LinkedHashMap<Integer,Integer>();int[] index=new int[values.length];
        for(int i=0;i<values.length;i++)index[i]=palette.computeIfAbsent(values[i],k->palette.size());
        int bits=palette.size()==1?0:Math.max(block?4:1,32-Integer.numberOfLeadingZeros(palette.size()-1));
        return new WireSectionCursor.WireContainer(bits,palette.keySet().stream().mapToInt(i->i).toArray(),bits==0?new long[0]:WireSectionCursor.pack(index,bits));
    }
}
