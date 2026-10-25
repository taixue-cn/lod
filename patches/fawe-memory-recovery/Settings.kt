package com.fastasyncworldedit.core.configuration
class Settings {
    @JvmField var MAX_MEMORY_PERCENT = 95
    @JvmField var SLOWER_MEMORY_PERCENT = 80

    companion object {
        private val instance = Settings()

        @JvmStatic fun settings() = instance
    }
}
