package cc.taixue.lod.server

import dev.vox.lss.common.wire.IdentityCodec
import dev.vox.lss.common.wire.WireSectionCursor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.block.data.BlockData

/** Reads only immutable CraftChunkSnapshot copies, never live NMS chunks or palettes. */
internal class PaletteSnapshotEncoder(snapshotType: Class<*>) {
    private fun field(type: Class<*>, name: String): Field =
        type.getDeclaredField(name).apply { isAccessible = true }

    // Paper 1.21/26.3 snapshot layout. Unsupported implementations use the Bukkit API fallback.
    private val blocks = field(snapshotType, "blockIds")
    private val biomeField = field(snapshotType, "biome")
    private val emitted = field(snapshotType, "emitLight")
    private val sky = field(snapshotType, "skylight")
    private val minimum = field(snapshotType, "minHeight")
    private val containerType = Class.forName("net.minecraft.world.level.chunk.PalettedContainer")
    private val dataField = field(containerType, "data")
    private val dataType = dataField.type
    private val storageMethod = dataType.getMethod("storage")
    private val paletteMethod = dataType.getMethod("palette")
    private val storageType = Class.forName("net.minecraft.util.BitStorage")
    private val bitsMethod = storageType.getMethod("getBits")
    private val rawMethod = storageType.getMethod("getRaw")
    private val unpackMethod = storageType.getMethod("unpack", IntArray::class.java)
    private val paletteType = Class.forName("net.minecraft.world.level.chunk.Palette")
    private val sizeMethod = paletteType.getMethod("getSize")
    private val valueMethod = paletteType.getMethod("valueFor", Int::class.javaPrimitiveType)
    private val stateType = Class.forName("net.minecraft.world.level.block.state.BlockState")
    private val getBlockMethod = containerType.getMethod("get", Int::class.javaPrimitiveType)
    private val blockDataMethod = stateType.getMethod("asBlockData")

    internal data class State(
        val identity: String,
        val air: Boolean,
        val fluid: Boolean,
        val properties: Map<String, String>,
    )

    private val states = ConcurrentHashMap<Any, State>()
    // Snapshots own immutable palettes; weak keys release section metadata with their snapshots.
    private val uniformSections =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())
    private val mixedSection = Any()

    private fun uniform(container: Any): Any =
        uniformSections[container]
            ?: run {
                val data = dataField.get(container)
                val storage = storageMethod.invoke(data)
                val palette = paletteMethod.invoke(data)
                val value =
                    if (bitsMethod.invoke(storage) == 0) valueMethod.invoke(palette, 0)
                    else {
                        val indices = IntArray(4096).also { unpackMethod.invoke(storage, it) }
                        if (indices.all { it == indices[0] })
                            valueMethod.invoke(palette, indices[0])
                        else mixedSection
                    }
                uniformSections[container] = value
                value
            }

    private fun state(value: Any): State =
        states.computeIfAbsent(value) {
            val data = blockDataMethod.invoke(it) as BlockData
            val text = data.asString
            val bracket = text.indexOf('[')
            val properties =
                if (bracket < 0) emptyMap()
                else
                    text
                        .substring(bracket + 1, text.length - 1)
                        .split(',')
                        .associate { property ->
                            val pair = property.split('=', limit = 2)
                            pair[0] to pair[1]
                        }
                        .toSortedMap()
            val identity =
                if (bracket < 0) text
                else IdentityCodec.canonical(text.substring(0, bracket), properties)
            State(
                identity,
                data.material.isAir,
                data.material == Material.WATER || data.material == Material.LAVA,
                java.util.Collections.unmodifiableMap(properties),
            )
        }

    fun stateAt(snapshot: ChunkSnapshot, x: Int, y: Int, z: Int): State {
        val containers = blocks.get(snapshot) as Array<*>
        val section = (y - minimum.getInt(snapshot)) shr 4
        val index = ((y and 15) shl 8) or (z shl 4) or x
        return state(getBlockMethod.invoke(containers[section], index))
    }

    /** Counts an identical vertical state run; uniform sections skip sixteen lookups at once. */
    fun runLength(snapshot: ChunkSnapshot, x: Int, y: Int, z: Int, lowerY: Int): Int {
        val containers = blocks.get(snapshot) as Array<*>
        val baseY = minimum.getInt(snapshot)
        val expected =
            getBlockMethod.invoke(
                containers[(y - baseY) shr 4],
                ((y and 15) shl 8) or (z shl 4) or x,
            )
        // Each beacon has an observable entry in DH's beacon list.
        if (state(expected).identity.startsWith("minecraft:beacon")) return 1
        var cursor = y
        var count = 0
        while (cursor >= lowerY) {
            val section = (cursor - baseY) shr 4
            val container = checkNotNull(containers[section])
            val uniform = uniform(container)
            if (uniform !== mixedSection) {
                if (uniform !== expected) break
                val length = cursor - maxOf(lowerY, baseY + section * 16) + 1
                count += length
                cursor -= length
            } else {
                val bottom = maxOf(lowerY, baseY + section * 16)
                while (cursor >= bottom) {
                    val index = ((cursor and 15) shl 8) or (z shl 4) or x
                    if (getBlockMethod.invoke(container, index) !== expected) return maxOf(1, count)
                    count++
                    cursor--
                }
            }
        }
        return maxOf(1, count)
    }

    fun encode(snapshot: ChunkSnapshot, minY: Int, maxY: Int): ByteArray {
        val containers = blocks.get(snapshot) as Array<*>
        // NMS 26.3 getMaxY is inclusive; Bukkit maxHeight remains exclusive.
        require(minimum.getInt(snapshot) == minY && containers.size * 16 == maxY - minY)
        val biomeContainers = biomeField.get(snapshot) as Array<*>
        val blockLights = emitted.get(snapshot) as Array<*>
        val skyLights = sky.get(snapshot) as Array<*>
        val dictionary = linkedMapOf<String, Int>()
        val sections = ArrayList<WireSectionCursor.WireSection>(containers.size)
        for (section in containers.indices) {
            val data = dataField.get(containers[section])
            val storage = storageMethod.invoke(data)
            val palette = paletteMethod.invoke(data)
            val bits = bitsMethod.invoke(storage) as Int
            val size = sizeMethod.invoke(palette) as Int
            // Global palettes may expose the whole registry; translate only used states below.
            val unpacked =
                if (bits == 0) intArrayOf(0)
                else IntArray(4096).also { unpackMethod.invoke(storage, it) }
            val single = bits == 0 || unpacked.all { it == unpacked[0] }
            val indices = if (single) intArrayOf(unpacked[0]) else unpacked
            val remap = IntArray(size) { -1 }
            val descriptions = arrayOfNulls<State>(size)
            var count = 0
            var used = 0
            var fluids = 0
            for (index in indices.indices) {
                val id = indices[index]
                val state =
                    descriptions[id]
                        ?: state(valueMethod.invoke(palette, id)).also {
                            descriptions[id] = it
                            used++
                            remap[id] = dictionary.getOrPut(it.identity) { dictionary.size }
                        }
                val weight = if (single) 4096 else 1
                if (!state.air) count += weight
                if (state.fluid) fluids += weight
            }
            val blockContainer =
                if (single) {
                    WireSectionCursor.WireContainer(0, intArrayOf(remap[indices[0]]), longArrayOf())
                } else if (size <= 256 && used == size) {
                    // Wire v20 and Paper both pack floor(64/bits) entries into each long.
                    WireSectionCursor.WireContainer(
                        bits,
                        remap,
                        (rawMethod.invoke(storage) as LongArray).copyOf(),
                    )
                } else {
                    for (index in indices.indices) indices[index] = remap[indices[index]]
                    ColumnEncoder.container(indices, true)
                }
            val sectionY = minY / 16 + section
            val biomeData = dataField.get(biomeContainers[section])
            val biomeStorage = storageMethod.invoke(biomeData)
            val biomeBits = bitsMethod.invoke(biomeStorage) as Int
            val biomePalette = paletteMethod.invoke(biomeData)
            val biomeRemap = IntArray(sizeMethod.invoke(biomePalette) as Int) { -1 }
            val biomeIndices =
                if (biomeBits == 0) intArrayOf(0)
                else IntArray(64).also { unpackMethod.invoke(biomeStorage, it) }
            val biomes = IntArray(biomeIndices.size)
            for (index in biomeIndices.indices) {
                val id = biomeIndices[index]
                if (biomeRemap[id] < 0) {
                    // Query one representative per palette entry to preserve Bukkit custom-biome
                    // semantics.
                    val biome =
                        snapshot
                            .getBiome(
                                (index and 3) * 4,
                                sectionY * 16 + (index shr 4) * 4,
                                ((index shr 2) and 3) * 4,
                            )
                            .key
                            .toString()
                    biomeRemap[id] = dictionary.getOrPut(biome) { dictionary.size }
                }
                biomes[index] = biomeRemap[id]
            }
            val biomeContainer =
                if (biomeBits == 0) WireSectionCursor.WireContainer(0, biomes, longArrayOf())
                else ColumnEncoder.container(biomes, false)
            fun light(values: Array<*>): ByteArray? =
                (values[section] as ByteArray).let { bytes ->
                    if (bytes.all { it == 0.toByte() }) null else bytes.copyOf()
                }
            sections +=
                WireSectionCursor.WireSection(
                    sectionY,
                    count,
                    fluids,
                    blockContainer,
                    biomeContainer,
                    light(blockLights),
                    light(skyLights),
                )
        }
        return WireSectionCursor.emit(
            WireSectionCursor.WireColumn(dictionary.keys.toList(), sections),
            WireSectionCursor.Layout.V20,
        )
    }
}
