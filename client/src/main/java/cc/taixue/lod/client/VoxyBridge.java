package cc.taixue.lod.client;

import cc.taixue.lod.core.Wire;
import dev.vox.lss.common.wire.*;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.*;
import java.lang.reflect.*;
import java.util.*;

/** Uses Voxy's public ingest surface. Voxy is optional and is never bundled or modified. */
final class VoxyBridge {
    private final Method worldOf,rawIngest;
    VoxyBridge() throws ReflectiveOperationException {
        Class<?> wi=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        worldOf=wi.getMethod("of",net.minecraft.world.level.Level.class);
        rawIngest=Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService").getMethod("rawIngest",wi,LevelChunkSection.class,int.class,int.class,int.class,DataLayer.class,DataLayer.class);
    }
    boolean ingest(ClientLevel level,int cx,int cz,byte[] body) throws ReflectiveOperationException {
        Object id=worldOf.invoke(null,level);if(id==null)return false;
        var registry=level.registryAccess().lookupOrThrow(Registries.BIOME);
        var factory=PalettedContainerFactory.create(level.registryAccess());
        var biomeIds=registry.asHolderIdMap();
        var plains=registry.get(Identifier.parse("minecraft:plains")).orElseThrow();
        byte[] nativeBody=V20ToNativeTranslator.translate(body,
            s->Block.BLOCK_STATE_REGISTRY.getId(resolve(s)),
            s->biomeIds.getId(registry.get(Identifier.parse(s)).orElse(plains)),
            Block.BLOCK_STATE_REGISTRY.size(),biomeIds.size());
        var buf=new FriendlyByteBuf(Unpooled.wrappedBuffer(nativeBody));
        try {
            int count=buf.readVarInt();if(count<0||count>level.getSectionsCount())throw new IllegalArgumentException("section count");
            for(int i=0;i<count;i++) {
                int sy=buf.readByte();if(sy<level.getMinSectionY()||sy>level.getMaxSectionY())throw new IllegalArgumentException("section height");
                var section=new LevelChunkSection(factory);section.read(buf);
                DataLayer block=buf.readBoolean()?new DataLayer(readLight(buf)):null;
                DataLayer sky=buf.readBoolean()?new DataLayer(readLight(buf)):null;
                boolean accepted=(boolean)rawIngest.invoke(null,id,section,cx,sy,cz,block,sky);
                if(!accepted)return false;
            }
            if(buf.isReadable())throw new IllegalArgumentException("trailing section bytes");return true;
        }finally{buf.release();}
    }
    private static byte[] readLight(FriendlyByteBuf b){byte[] v=new byte[2048];b.readBytes(v);return v;}
    static BlockState resolve(String identity) {
        var parsed=IdentityCodec.parse(identity);
        var block=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(parsed.name())).orElse(Blocks.STONE);
        BlockState state=block.defaultBlockState();
        for(var entry:parsed.properties()) {
            Property<?> property=block.getStateDefinition().getProperty(entry.getKey());
            if(property!=null)state=apply(state,property,entry.getValue());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState apply(BlockState s,Property<T> p,String value){return p.getValue(value).map(v->s.setValue(p,v)).orElse(s);}
}
