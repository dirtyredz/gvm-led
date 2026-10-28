package com.dirtyredz.gvmled.update

/**
 * The release version scheme of `src/mobile/releases/version.js`: release sequence n is version
 * `0.1.n`, the patch rolling into the minor every [PATCH_INTERVAL] releases, and versionCode is
 * n + 1. The app compares version codes only; names are display text and a consistency check.
 */
object ReleaseVersion {
    const val PATCH_INTERVAL = 60_000L
    private const val FIRST_MINOR = 1L
    const val MAX_SEQUENCE = PATCH_INTERVAL * PATCH_INTERVAL * PATCH_INTERVAL - 1 - FIRST_MINOR * PATCH_INTERVAL
    private val VERSION = Regex("(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})")

    /** The version of [sequence] (1 -> 0.1.1), or null when out of range. */
    fun versionForSequence(sequence: Long): String? {
        if (sequence < 1 || sequence > MAX_SEQUENCE) return null
        val total = FIRST_MINOR * PATCH_INTERVAL + sequence
        val minors = total / PATCH_INTERVAL
        return "${minors / PATCH_INTERVAL}.${minors % PATCH_INTERVAL}.${total % PATCH_INTERVAL}"
    }

    /** The sequence a canonical version stands for, or null for anything else. */
    fun sequenceForVersion(version: String): Long? {
        val match = VERSION.matchEntire(version) ?: return null
        val (major, minor, patch) = match.groupValues.drop(1).map(String::toLong)
        if (minor >= PATCH_INTERVAL || patch >= PATCH_INTERVAL) return null
        val sequence = (major * PATCH_INTERVAL + minor) * PATCH_INTERVAL + patch - FIRST_MINOR * PATCH_INTERVAL
        return sequence.takeIf { it in 1..MAX_SEQUENCE }
    }

    /** The only versionName a release with [versionCode] may carry. */
    fun versionNameForCode(versionCode: Long): String? = versionForSequence(versionCode - 1)
}
