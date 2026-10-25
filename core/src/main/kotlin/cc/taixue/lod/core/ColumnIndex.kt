package cc.taixue.lod.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Compact per-region versions avoid a boxed key, UUID and map node for every cached column. */
class ColumnIndex {
    private class Region {
        val versions = LongArray(1024) { Long.MIN_VALUE }
    }

    private val worlds = ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Region>>()

    private fun position(key: ColumnCache.Key) =
        Math.floorMod(key.x, 32) * 32 + Math.floorMod(key.z, 32)

    private fun region(key: ColumnCache.Key) =
        Wire.pack(Math.floorDiv(key.x, 32), Math.floorDiv(key.z, 32))

    operator fun get(key: ColumnCache.Key): Long? {
        val values = worlds[key.world]?.get(region(key)) ?: return null
        return synchronized(values) {
            values.versions[position(key)].takeUnless { it == Long.MIN_VALUE }
        }
    }

    operator fun set(key: ColumnCache.Key, version: Long) = put(key, version, false)

    fun putIfAbsent(key: ColumnCache.Key, version: Long) = put(key, version, true)

    private fun put(key: ColumnCache.Key, version: Long, onlyAbsent: Boolean) {
        require(version >= 0)
        val values =
            worlds
                .computeIfAbsent(key.world) { ConcurrentHashMap() }
                .computeIfAbsent(region(key)) { Region() }
        synchronized(values) {
            val index = position(key)
            if (!onlyAbsent || values.versions[index] == Long.MIN_VALUE)
                values.versions[index] = version
        }
    }

    /** Filter whole regions before expanding columns; snapshots hold only one region at a time. */
    fun nearby(world: UUID, x: Int, z: Int, radius: Int): Sequence<Pair<ColumnCache.Key, Long>> =
        sequence {
            require(radius >= 0)
            val regions = worlds[world] ?: return@sequence
            for ((position, values) in regions) {
                val rx = Wire.x(position)
                val rz = Wire.z(position)
                if (
                    rx.toLong() * 32 > x.toLong() + radius ||
                        rx.toLong() * 32 + 31 < x.toLong() - radius ||
                        rz.toLong() * 32 > z.toLong() + radius ||
                        rz.toLong() * 32 + 31 < z.toLong() - radius
                )
                    continue
                val versions = synchronized(values) { values.versions.copyOf() }
                for (index in versions.indices) {
                    if (versions[index] <= 0) continue
                    val cx = rx * 32 + index / 32
                    val cz = rz * 32 + index % 32
                    if (
                        kotlin.math.abs(cx.toLong() - x) <= radius &&
                            kotlin.math.abs(cz.toLong() - z) <= radius
                    )
                        yield(ColumnCache.Key(world, cx, cz) to versions[index])
                }
            }
        }
}
