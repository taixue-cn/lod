package cc.taixue.lod.client

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** Each channel has its own raw codec. Check the length before copying untrusted data. */
internal data class Frame(
    private val payloadType: CustomPacketPayload.Type<Frame>,
    val bytes: ByteArray,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<Frame> = payloadType

    companion object {
        fun type(identifier: String): CustomPacketPayload.Type<Frame> =
            CustomPacketPayload.Type(Identifier.parse(identifier))

        fun codec(
            type: CustomPacketPayload.Type<Frame>,
            maximum: Int,
        ): StreamCodec<FriendlyByteBuf, Frame> =
            StreamCodec.of(
                { buffer, frame -> buffer.writeBytes(frame.bytes) },
                { buffer ->
                    val size = buffer.readableBytes()
                    require(size <= maximum) { "LOD frame too large" }
                    Frame(type, ByteArray(size).also { buffer.readBytes(it) })
                },
            )
    }
}
