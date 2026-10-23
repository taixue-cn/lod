package cc.taixue.lod.core

import java.util.Locale
import java.util.regex.Pattern

/** Immutable policy matching world names, dimension keys or UUIDs; only '*' is a wildcard. */
class WorldPolicy(mode: String, entries: Collection<String>) {
    enum class Mode {
        WHITELIST,
        BLACKLIST,
    }

    private val mode = Mode.valueOf(mode.uppercase(Locale.ROOT))
    private val patterns = entries.map { entry ->
        Pattern.compile(
            entry.split('*').joinToString(".*") { Pattern.quote(it) },
            Pattern.CASE_INSENSITIVE,
        )
    }

    fun allows(vararg identifiers: String?): Boolean {
        val listed = patterns.any { pattern ->
            identifiers.filterNotNull().any { pattern.matcher(it).matches() }
        }
        return if (mode == Mode.WHITELIST) listed else !listed
    }
}
