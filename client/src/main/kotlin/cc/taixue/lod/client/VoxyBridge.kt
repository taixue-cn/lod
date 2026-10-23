package cc.taixue.lod.client

import dev.vox.lss.common.wire.IdentityCodec
import dev.vox.lss.common.wire.V20ToNativeTranslator
import io.netty.buffer.Unpooled
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerFactory

/** Uses Voxy's ingest API reflectively, keeping the unmodified Voxy mod optional. */
internal class VoxyBridge @Throws(ReflectiveOperationException::class) constructor() {
    private val worldIdentifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier")
    private val worldOf = worldIdentifier.getMethod("of", Level::class.java)
    private val rawIngest =
        Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService")
            .getMethod(
                "rawIngest",
                worldIdentifier,
                LevelChunkSection::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                DataLayer::class.java,
                DataLayer::class.java,
            )

    @Throws(ReflectiveOperationException::class)
    fun ingest(level: ClientLevel, x: Int, z: Int, body: ByteArray): Boolean {
        val identifier = worldOf.invoke(null, level) ?: return false
        val registry = level.registryAccess().lookupOrThrow(Registries.BIOME)
        val factory = PalettedContainerFactory.create(level.registryAccess())
        val biomeIds = registry.asHolderIdMap()
        val plains = registry.get(Identifier.parse("minecraft:plains")).orElseThrow()
        val native =
            V20ToNativeTranslator.translate(
                body,
                { Block.BLOCK_STATE_REGISTRY.getId(resolve(it)) },
                { biomeIds.getId(registry.get(Identifier.parse(it)).orElse(plains)) },
                Block.BLOCK_STATE_REGISTRY.size(),
                biomeIds.size(),
            )
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(native))
        try {
            val count = buffer.readVarInt()
            require(count in 0..level.sectionsCount) { "section count" }
            repeat(count) {
                val sectionY = buffer.readByte().toInt()
                require(sectionY in level.minSectionY..level.maxSectionY) { "section height" }
                val section = LevelChunkSection(factory).apply { read(buffer) }
                val block = if (buffer.readBoolean()) DataLayer(readLight(buffer)) else null
                val sky = if (buffer.readBoolean()) DataLayer(readLight(buffer)) else null
                if (
                    !(rawIngest.invoke(null, identifier, section, x, sectionY, z, block, sky)
                        as Boolean)
                )
                    return false
            }
            require(!buffer.isReadable) { "trailing section bytes" }
            return true
        } finally {
            buffer.release()
        }
    }

    private fun readLight(buffer: FriendlyByteBuf): ByteArray =
        ByteArray(2048).also { buffer.readBytes(it) }

    private fun resolve(identity: String): BlockState {
        val parsed = IdentityCodec.parse(identity)
        val block =
            BuiltInRegistries.BLOCK.getOptional(Identifier.parse(parsed.name()))
                .orElse(Blocks.STONE)
        var state = block.defaultBlockState()
        for (entry in parsed.properties()) {
            val property = block.stateDefinition.getProperty(entry.key) ?: continue
            state = apply(state, property, entry.value)
        }
        return state
    }

    private fun <T : Comparable<T>> apply(
        state: BlockState,
        property: Property<T>,
        value: String,
    ): BlockState = property.getValue(value).map { state.setValue(property, it) }.orElse(state)
}
