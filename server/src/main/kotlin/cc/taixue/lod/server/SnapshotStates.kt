package cc.taixue.lod.server

import org.bukkit.ChunkSnapshot

/**
 * Registry-only state strings/properties for DH; mapped materials and dummy worlds stay
 * upstream-owned.
 */
object SnapshotStates {
    @JvmStatic
    fun identity(snapshot: ChunkSnapshot, x: Int, y: Int, z: Int): String? =
        ColumnEncoder.palette(snapshot)?.stateAt(snapshot, x, y, z)?.identity

    @JvmStatic
    fun properties(snapshot: ChunkSnapshot, x: Int, y: Int, z: Int): Map<String, String>? =
        ColumnEncoder.palette(snapshot)?.stateAt(snapshot, x, y, z)?.properties
}
