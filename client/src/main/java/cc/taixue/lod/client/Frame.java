package cc.taixue.lod.client;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Each registered channel has an independent raw codec; length is bounded before copy. */
public record Frame(Type<Frame> type,byte[] bytes) implements CustomPacketPayload {
    static Type<Frame> type(String id){return new Type<>(Identifier.parse(id));}
    static StreamCodec<FriendlyByteBuf,Frame> codec(Type<Frame> type,int maximum){
        return StreamCodec.of((buf,frame)->buf.writeBytes(frame.bytes),(buf)->{
            int n=buf.readableBytes();if(n>maximum)throw new IllegalArgumentException("LOD frame too large");
            byte[] data=new byte[n];buf.readBytes(data);return new Frame(type,data);
        });
    }
}
