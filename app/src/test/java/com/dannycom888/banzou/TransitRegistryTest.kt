package com.dannycom888.banzou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitRegistryTest {

    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3A%E4%B8%AD%E8%BD%AC"
    private fun docUri(docId: String) =
        "content://com.android.externalstorage.documents/tree/primary%3A%E4%B8%AD%E8%BD%AC/document/$docId"

    @Test
    fun `records survive encode decode round trip with chinese names`() {
        val records = listOf(
            TransitRegistry.Record(
                sourceUri = docUri("primary%3A%E4%B8%AD%E8%BD%AC%2F%E4%BB%BB%E8%B4%A4%E9%BD%90%20-%20%E5%BF%83%E5%A4%AA%E8%BD%AF.mp3"),
                sourceName = "任贤齐 - 心太软.mp3",
                accompanimentUri = docUri("primary%3A%E4%B8%AD%E8%BD%AC%2Facc.mp3"),
                accompanimentName = "任贤齐 - 心太软（伴奏）.mp3",
            ),
        )
        assertEquals(records, TransitRegistry.decode(TransitRegistry.encode(records)))
    }

    @Test
    fun `decode tolerates empty and garbage`() {
        assertTrue(TransitRegistry.decode(null).isEmpty())
        assertTrue(TransitRegistry.decode("").isEmpty())
        assertTrue(TransitRegistry.decode("not-a-record").isEmpty())
    }

    @Test
    fun `documents under the picked tree belong to it`() {
        assertTrue(TransitRegistry.belongsToTree(docUri("primary%3Aanything.mp3"), treeUri))
    }

    @Test
    fun `documents from another tree do not belong`() {
        val otherTreeDoc =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.mp3"
        assertFalse(TransitRegistry.belongsToTree(otherTreeDoc, treeUri))
    }

    @Test
    fun `the car music library is not under a transit tree`() {
        // The user's real library lives in the Music folder, reached through
        // its own tree id (primary:Music). A transit tree (primary:中转)
        // must never claim it.
        val musicTreeUri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val musicDoc =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F%E4%BB%BB%E8%B4%A4%E9%BD%90%20-%20%E5%BF%83%E5%A4%AA%E8%BD%AF.mp3"
        assertFalse(TransitRegistry.belongsToTree(musicDoc, treeUri))
        assertTrue(TransitRegistry.belongsToTree(musicDoc, musicTreeUri))
    }

    @Test
    fun `subfolder documents under the transit tree belong to it`() {
        // Transit copies may sit in a subfolder of the picked folder.
        val nested = docUri("primary%3A%E4%B8%AD%E8%BD%AC%2F%E5%AD%90%E6%96%87%E4%BB%B6%E5%A4%B9%2Fsong.mp3")
        assertTrue(TransitRegistry.belongsToTree(nested, treeUri))
    }
}
