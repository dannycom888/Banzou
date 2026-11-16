package com.dannycom888.banzou

import android.content.Context

/**
 * Registry of "transit copies": songs this app has processed, recorded so
 * the user can later clean up the phone's transit workspace in one tap.
 *
 * Safety design (hard rules):
 * - Only files this app itself processed get registered — never arbitrary
 *   files the user happens to have.
 * - Cleanup only ever touches records whose URIs live under the folder
 *   tree the user picked as the transit workspace ([belongsToTree]).
 * - The user's real library (the car Music library) is never scanned into
 *   this registry; deletion always goes through an explicit confirmation
 *   dialog listing every file (see MainActivity).
 *
 * Records persist in SharedPreferences so "export today, clean tomorrow"
 * works across restarts.
 */
object TransitRegistry {

    data class Record(
        val sourceUri: String,
        val sourceName: String,
        val accompanimentUri: String,
        val accompanimentName: String,
    )

    private const val PREFS = "banzou_transit"
    private const val KEY = "records"
    private const val SEP = "\u001F"

    fun encode(records: List<Record>): String =
        records.joinToString("\n") { r ->
            listOf(r.sourceUri, r.sourceName, r.accompanimentUri, r.accompanimentName)
                .joinToString(SEP)
        }

    fun decode(raw: String?): List<Record> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size == 4) Record(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }

    /**
     * True when a document URI belongs to the picked tree. SAF document
     * URIs look like `.../tree/<treeDocId>/document/<docId>` while the
     * tree URI itself is `.../tree/<treeDocId>`; a document under the
     * tree always contains "/tree/<treeDocId>/document/".
     */
    fun belongsToTree(documentUri: String, treeUri: String): Boolean {
        val marker = "/tree/"
        val idx = treeUri.indexOf(marker)
        if (idx < 0) return false
        val treeDocId = treeUri.substring(idx + marker.length).trimEnd('/')
        if (treeDocId.isEmpty()) return false
        return documentUri.contains("$marker$treeDocId/document/")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Record> = decode(prefs(context).getString(KEY, null))

    fun add(context: Context, record: Record) {
        val current = all(context).filterNot {
            it.sourceUri == record.sourceUri || it.accompanimentUri == record.accompanimentUri
        }
        prefs(context).edit().putString(KEY, encode(current + record)).apply()
    }

    fun remove(context: Context, records: Collection<Record>) {
        val drop = records.map { it.accompanimentUri }.toSet()
        val kept = all(context).filterNot { it.accompanimentUri in drop }
        prefs(context).edit().putString(KEY, encode(kept)).apply()
    }

    /** Records whose BOTH files live under [treeUri] — the only cleanable ones. */
    fun cleanable(context: Context, treeUri: String?): List<Record> {
        if (treeUri == null) return emptyList()
        return all(context).filter {
            belongsToTree(it.sourceUri, treeUri) && belongsToTree(it.accompanimentUri, treeUri)
        }
    }
}
