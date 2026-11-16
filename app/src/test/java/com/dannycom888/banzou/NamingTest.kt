package com.dannycom888.banzou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {

    @Test
    fun `chinese name gets accompaniment suffix`() {
        assertEquals("任贤齐 - 心太软（伴奏）.mp3", Naming.accompanimentName("任贤齐 - 心太软.mp3"))
    }

    @Test
    fun `english name gets accompaniment suffix`() {
        assertEquals(
            "Marc Anthony - You Sang To Me（伴奏）.mp3",
            Naming.accompanimentName("Marc Anthony - You Sang To Me.mp3"),
        )
    }

    @Test
    fun `uppercase extension is normalised but recognised`() {
        assertTrue(Naming.isMp3("Song.MP3"))
        assertEquals("Song（伴奏）.mp3", Naming.accompanimentName("Song.MP3"))
    }

    @Test
    fun `accompaniment files are recognised and not listable`() {
        assertTrue(Naming.isAccompaniment("任贤齐 - 心太软（伴奏）.mp3"))
        assertFalse(Naming.isListableSong("任贤齐 - 心太软（伴奏）.mp3"))
        assertTrue(Naming.isListableSong("任贤齐 - 心太软.mp3"))
    }

    @Test
    fun `temp files are not listable`() {
        assertFalse(Naming.isListableSong("任贤齐 - 心太软（伴奏）.mp3.part"))
        assertTrue(Naming.isTempName("任贤齐 - 心太软（伴奏）.mp3.part"))
    }

    @Test
    fun `hasAccompaniment checks the folder listing`() {
        val names = setOf("任贤齐 - 心太软.mp3", "任贤齐 - 心太软（伴奏）.mp3", "任贤齐 - 心太软.lrc")
        assertTrue(Naming.hasAccompaniment(names, "任贤齐 - 心太软.mp3"))
        assertFalse(Naming.hasAccompaniment(names - "任贤齐 - 心太软（伴奏）.mp3", "任贤齐 - 心太软.mp3"))
    }

    @Test
    fun `temp name appends part suffix`() {
        assertEquals(
            "任贤齐 - 心太软（伴奏）.mp3.part",
            Naming.tempName("任贤齐 - 心太软（伴奏）.mp3"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to make accompaniment of an accompaniment`() {
        Naming.accompanimentName("任贤齐 - 心太软（伴奏）.mp3")
    }
}
