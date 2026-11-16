package com.dannycom888.banzou

/**
 * File-naming rules for accompaniment generation. Pure logic, unit-tested.
 *
 * Rules (fixed by the product spec, do not change casually):
 * - An accompaniment lives next to its original: same folder, same name,
 *   with "（伴奏）" appended to the stem: `任贤齐 - 心太软.mp3` ->
 *   `任贤齐 - 心太软（伴奏）.mp3`.
 * - Accompaniment files themselves are never offered as source songs.
 * - A song that already has an accompaniment is skipped, never overwritten.
 * - Work in progress goes to a temporary name and is only renamed to the
 *   final name after it is fully written, so a half-written file can never
 *   be mistaken for a finished accompaniment.
 */
object Naming {
    const val ACCOMPANIMENT_SUFFIX = "（伴奏）"
    const val TEMP_SUFFIX = ".part"

    fun isMp3(name: String): Boolean = name.lowercase().endsWith(".mp3")

    fun stemOf(name: String): String = name.substringBeforeLast('.', name)

    fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

    /** True for files that are themselves generated accompaniments. */
    fun isAccompaniment(name: String): Boolean =
        isMp3(name) && stemOf(name).endsWith(ACCOMPANIMENT_SUFFIX)

    /** `任贤齐 - 心太软.mp3` -> `任贤齐 - 心太软（伴奏）.mp3` */
    fun accompanimentName(originalName: String): String {
        require(isMp3(originalName)) { "not an mp3: $originalName" }
        require(!isAccompaniment(originalName)) { "already an accompaniment: $originalName" }
        return stemOf(originalName) + ACCOMPANIMENT_SUFFIX + "." + extensionOf(originalName)
    }

    /** Temporary in-progress name for a final file name. */
    fun tempName(finalName: String): String = finalName + TEMP_SUFFIX

    fun isTempName(name: String): Boolean = name.endsWith(TEMP_SUFFIX)

    /** True when [allNames] (one folder's listing) already contains the accompaniment for [songName]. */
    fun hasAccompaniment(allNames: Collection<String>, songName: String): Boolean =
        allNames.contains(accompanimentName(songName))

    /** Songs worth listing: mp3 files that are not accompaniments and not temp files. */
    fun isListableSong(name: String): Boolean =
        isMp3(name) && !isAccompaniment(name) && !isTempName(name)
}
