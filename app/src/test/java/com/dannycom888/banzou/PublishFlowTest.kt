package com.dannycom888.banzou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Drives the production [Publish.publishWithTemp] sequence through a
 * plain java.io.File store with REAL Chinese file names — the same
 * names the app will create on the phone — covering success, the
 * no-rename fallback, failure cleanup, and the never-overwrite rule.
 * Also the end-to-end naming chain: original Chinese MP3 name ->
 * temp name -> final accompaniment name, byte-for-byte.
 */
class PublishFlowTest {

    private fun newDir(): File = Files.createTempDirectory("banzou-test").toFile()

    private class FileStore(val dir: File) {
        fun exists(name: String) = File(dir, name).exists()
        fun delete(name: String) {
            File(dir, name).delete()
        }
        fun create(name: String): File? {
            val f = File(dir, name)
            return if (f.createNewFile()) f else null
        }
        fun rename(f: File, newName: String) = f.renameTo(File(dir, newName))
        fun copy(from: File, to: File) {
            from.inputStream().use { i -> to.outputStream().use { i.copyTo(it) } }
        }
        fun handleOf(name: String): File? = File(dir, name).takeIf { it.exists() }
    }

    private fun publish(
        store: FileStore,
        songName: String,
        content: ByteArray,
        renameWorks: Boolean = true,
        writeFails: Boolean = false,
    ): File {
        val finalName = Naming.accompanimentName(songName)
        val tempName = Naming.tempName(finalName)
        return Publish.publishWithTemp(
            finalName = finalName,
            tempName = tempName,
            exists = store::exists,
            delete = store::delete,
            create = store::create,
            write = { f ->
                if (writeFails) throw IllegalStateException("模拟写入失败")
                f.writeBytes(content)
            },
            rename = { f, newName -> if (renameWorks) store.rename(f, newName) else false },
            copy = store::copy,
            handleOf = store::handleOf,
        )
    }

    @Test
    fun `chinese song end to end success via rename`() {
        val dir = newDir()
        val store = FileStore(dir)
        val song = "任贤齐 - 心太软.mp3"
        File(dir, song).writeBytes(byteArrayOf(1, 2, 3)) // the "original", untouched below
        val content = "伴奏内容".toByteArray(Charsets.UTF_8)

        val result = publish(store, song, content)

        assertEquals("任贤齐 - 心太软（伴奏）.mp3", result.name)
        assertTrue(result.exists())
        assertEquals(content.toList(), result.readBytes().toList())
        assertFalse("temp file must be gone", File(dir, "任贤齐 - 心太软（伴奏）.mp3.part").exists())
        // Original untouched.
        assertEquals(listOf<Byte>(1, 2, 3), File(dir, song).readBytes().toList())
        dir.deleteRecursively()
    }

    @Test
    fun `success via copy fallback when rename is refused`() {
        val dir = newDir()
        val store = FileStore(dir)
        val result = publish(store, "任贤齐 - 心太软.mp3", byteArrayOf(9, 9), renameWorks = false)
        assertEquals("任贤齐 - 心太软（伴奏）.mp3", result.name)
        assertEquals(listOf<Byte>(9, 9), result.readBytes().toList())
        assertFalse(File(dir, "任贤齐 - 心太软（伴奏）.mp3.part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `write failure leaves neither temp nor final behind`() {
        val dir = newDir()
        val store = FileStore(dir)
        try {
            publish(store, "任贤齐 - 心太软.mp3", byteArrayOf(1), writeFails = true)
            fail("should have thrown")
        } catch (expected: IllegalStateException) {
        }
        assertFalse(File(dir, "任贤齐 - 心太软（伴奏）.mp3").exists())
        assertFalse(File(dir, "任贤齐 - 心太软（伴奏）.mp3.part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `existing accompaniment is never overwritten`() {
        val dir = newDir()
        val store = FileStore(dir)
        File(dir, "任贤齐 - 心太软（伴奏）.mp3").writeBytes(byteArrayOf(7))
        try {
            publish(store, "任贤齐 - 心太软.mp3", byteArrayOf(1))
            fail("should have thrown")
        } catch (expected: IllegalStateException) {
            assertEquals("已有伴奏，跳过", expected.message)
        }
        assertEquals(listOf<Byte>(7), File(dir, "任贤齐 - 心太软（伴奏）.mp3").readBytes().toList())
        dir.deleteRecursively()
    }

    @Test
    fun `stale temp from a crashed run is cleaned before writing`() {
        val dir = newDir()
        val store = FileStore(dir)
        File(dir, "任贤齐 - 心太软（伴奏）.mp3.part").writeBytes(byteArrayOf(5, 5, 5))
        val result = publish(store, "任贤齐 - 心太软.mp3", byteArrayOf(2))
        assertEquals(listOf<Byte>(2), result.readBytes().toList())
        dir.deleteRecursively()
    }
}
