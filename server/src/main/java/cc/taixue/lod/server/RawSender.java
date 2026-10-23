package cc.taixue.lod.server;

import org.bukkit.entity.Player;
import java.lang.reflect.*;

/** Paper 1.21.4 custom-payload egress; Fabric clients need not send minecraft:register. */
public final class RawSender {
    private final Method parse,send;
    private final Constructor<?> payload,packet;
    private final Field connection;
    public RawSender() throws ReflectiveOperationException {
        Class<?> id=Class.forName("net.minecraft.resources.ResourceLocation");
        parse=id.getMethod("parse",String.class);
        payload=Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload").getConstructor(id,byte[].class);
        packet=Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket").getConstructor(Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload"));
        connection=Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
        send=connection.getType().getMethod("send",Class.forName("net.minecraft.network.protocol.Packet"));
    }
    public void send(Player player,String channel,byte[] bytes) {
        if(bytes.length>1_048_000)throw new IllegalArgumentException("LOD frame too large");
        try {
            Object handle=player.getClass().getMethod("getHandle").invoke(player);
            send.invoke(connection.get(handle),packet.newInstance(payload.newInstance(parse.invoke(null,channel),bytes)));
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Paper LOD payload send failed",e);}
    }
}
