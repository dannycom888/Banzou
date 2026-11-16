package com.dannycom888.banzou

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.dannycom888.banzou.audio.Mp3Encoder
import com.dannycom888.banzou.audio.PcmDecoder
import com.dannycom888.banzou.audio.Separator
import java.io.File

/**
 * Full pipeline for one song:
 * decode -> separate (accompaniment PCM) -> MP3-encode into a TEMP file in
 * the song's own folder -> rename to the final "（伴奏）" name.
 * Any failure deletes the temp file: no half-finished accompaniment is
 * ever left behind. The original file is only ever read.
 */
object SongProcessor {

    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "model")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "UVR_MDXNET_9482.onnx")
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(dir, "model.tmp")
            context.assets.open("UVR_MDXNET_9482.onnx").use { input ->
                tmp.outputStream().buffered().use { input.copyTo(it) }
            }
            check(tmp.renameTo(target)) { "模型文件准备失败" }
        }
        return target
    }

    /**
     * @param onProgress 0f..1f across the whole song (separation dominates)
     * @return the created accompaniment DocumentFile
     */
    fun process(
        context: Context,
        separator: Separator,
        item: SongItem,
        isCancelled: () -> Boolean,
        onProgress: (Float, String) -> Unit,
    ): DocumentFile {
        val finalName = Naming.accompanimentName(item.name)
        val tempName = Naming.tempName(finalName)
        val parent = item.parent

        val workDir = File(context.cacheDir, "work").apply { mkdirs() }
        val mixPcm = File(workDir, "mix.pcm")
        val accPcm = File(workDir, "acc.pcm")
        try {
            onProgress(0.01f, "解码中")
            val decoded = PcmDecoder.decode(context, item.uri, mixPcm)
            if (isCancelled()) throw InterruptedException("已取消")

            onProgress(0.05f, "分轨中")
            separator.separate(
                pcmIn = decoded.pcmFile,
                pcmOut = accPcm,
                totalSamples = decoded.totalSamples,
                isCancelled = isCancelled,
                onProgress = { done, total ->
                    val frac = if (total == 0) 0f else done.toFloat() / total
                    onProgress(0.05f + 0.9f * frac, "分轨中 $done/$total 段")
                },
            )
            if (isCancelled()) throw InterruptedException("已取消")

            onProgress(0.97f, "编码 MP3 中")
            val result = Publish.publishWithTemp(
                finalName = finalName,
                tempName = tempName,
                exists = { name -> parent.findFile(name) != null },
                delete = { name -> parent.findFile(name)?.delete() },
                create = { name -> parent.createFile("audio/mpeg", name) },
                write = { doc ->
                    context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                        Mp3Encoder.encode(accPcm, decoded.totalSamples, out)
                    } ?: throw IllegalStateException("无法写入伴奏文件")
                },
                rename = { doc, newName ->
                    try {
                        doc.renameTo(newName)
                    } catch (e: Exception) {
                        false
                    }
                },
                copy = { from, to ->
                    context.contentResolver.openInputStream(from.uri)?.use { input ->
                        context.contentResolver.openOutputStream(to.uri, "w")?.use { out ->
                            input.copyTo(out)
                        }
                    }
                },
                handleOf = { name -> parent.findFile(name) },
            )
            onProgress(1f, "完成")
            return result
        } finally {
            mixPcm.delete()
            File(workDir, "mix.pcm.raw").delete()
            accPcm.delete()
        }
    }
}
