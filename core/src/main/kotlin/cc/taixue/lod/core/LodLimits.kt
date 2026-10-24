package cc.taixue.lod.core

/**
 * Shared horizontal download bounds; the administrator and player may each choose a lower radius.
 */
object LodLimits {
    const val MIN_RADIUS_CHUNKS = 8
    const val MAX_RADIUS_CHUNKS = 1024

    fun radius(value: Int): Int = value.coerceIn(MIN_RADIUS_CHUNKS, MAX_RADIUS_CHUNKS)
}
