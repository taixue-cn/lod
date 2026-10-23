package cc.taixue.lod.server

import org.bukkit.entity.Player

/** Paper 1.21.4 payload egress; Fabric clients need not send minecraft:register. */
class RawSender @Throws(ReflectiveOperationException::class) constructor() {
    private val identifier = Class.forName("net.minecraft.resources.ResourceLocation")
    private val parse = identifier.getMethod("parse", String::class.java)
    private val payload =
        Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload")
            .getConstructor(identifier, ByteArray::class.java)
    private val packet =
        Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket")
            .getConstructor(
                Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload")
            )
    private val connection =
        Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection")
    private val send =
        connection.type.getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))

    fun send(player: Player, channel: String, bytes: ByteArray) {
        require(bytes.size <= 1_048_000) { "LOD frame too large" }
        try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            val message = payload.newInstance(parse.invoke(null, channel), bytes)
            send.invoke(connection.get(handle), packet.newInstance(message))
        } catch (cause: ReflectiveOperationException) {
            throw IllegalStateException("Paper LOD payload send failed", cause)
        }
    }
}
