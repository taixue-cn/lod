package cc.taixue.lod.core

/** A separate, negotiated protocol keeps prepared Voxy data away from ordinary LSS receivers. */
object FastTerrain {
    const val HELLO = "taixue:lod_fast_hello"
    const val CONFIG = "taixue:lod_fast_config"
    const val INDEX = "taixue:lod_fast_index"
    const val REQUEST = "taixue:lod_fast_request"
    const val DATA = "taixue:lod_fast_data"
    const val WINDOW = 4096
    const val REQUEST_BATCH = 512
}
