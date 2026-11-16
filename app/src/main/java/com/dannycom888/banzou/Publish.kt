package com.dannycom888.banzou

/**
 * The temp-file publish dance, factored out so the exact sequence used in
 * production (SAF DocumentFile store) is unit-testable with a plain
 * java.io.File store:
 *
 * 1. If the final name already exists, refuse (never overwrite).
 * 2. Delete any stale temp file from an earlier crashed run.
 * 3. Create the temp file and write the content into it.
 * 4. Rename temp -> final; if the store cannot rename, copy + delete temp.
 * 5. On ANY failure, delete the temp file: a half-written accompaniment
 *    must never be left behind.
 */
object Publish {
    fun <T> publishWithTemp(
        finalName: String,
        tempName: String,
        exists: (String) -> Boolean,
        delete: (String) -> Unit,
        create: (String) -> T?,
        write: (T) -> Unit,
        rename: (T, String) -> Boolean,
        copy: (from: T, to: T) -> Unit,
        handleOf: (String) -> T?,
    ): T {
        if (exists(finalName)) {
            throw IllegalStateException("已有伴奏，跳过")
        }
        delete(tempName)
        val temp = create(tempName)
            ?: throw IllegalStateException("无法新建临时文件")
        try {
            write(temp)
            if (rename(temp, finalName)) {
                return handleOf(finalName) ?: temp
            }
            val finalDoc = create(finalName)
                ?: throw IllegalStateException("无法创建最终文件")
            copy(temp, finalDoc)
            delete(tempName)
            return finalDoc
        } catch (e: Exception) {
            try {
                delete(tempName)
            } catch (ignored: Exception) {
            }
            throw e
        }
    }
}
