package com.dannycom888.banzou

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** One listable song in the picked library folder. */
data class SongItem(
    val name: String,
    val file: DocumentFile,
    val parent: DocumentFile,
    val hasAccompaniment: Boolean,
) {
    val uri: Uri get() = file.uri
}

/**
 * SAF scan of the picked folder (recursive). Holds results in memory;
 * the persisted piece is only the tree URI (see MainActivity prefs).
 */
object SongLibrary {

    fun scan(tree: DocumentFile): List<SongItem> {
        val songs = mutableListOf<SongItem>()
        fun walk(dir: DocumentFile) {
            val children = dir.listFiles()
            val names = children.mapNotNull { it.name }.toSet()
            for (child in children) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name ?: continue
                    if (Naming.isListableSong(name)) {
                        songs += SongItem(
                            name = name,
                            file = child,
                            parent = dir,
                            hasAccompaniment = Naming.hasAccompaniment(names, name),
                        )
                    }
                }
            }
        }
        walk(tree)
        return songs.sortedBy { it.name.lowercase() }
    }

    fun treeFromUri(context: Context, uriString: String): DocumentFile? {
        val uri = Uri.parse(uriString)
        val doc = DocumentFile.fromTreeUri(context, uri)
        return if (doc != null && doc.exists() && doc.canRead()) doc else null
    }
}
