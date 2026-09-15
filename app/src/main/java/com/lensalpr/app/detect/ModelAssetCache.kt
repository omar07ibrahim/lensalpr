package com.lensalpr.app.detect

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A content-checked, atomically published copy of an immutable packaged model. */
internal object ModelAssetCache {
    @Synchronized
    fun materialize(target: File, openAsset: () -> InputStream): File {
        val expected = openAsset().use(::digest)
        if (target.isFile && runCatching { target.inputStream().use(::digest).contentEquals(expected) }
                .getOrDefault(false)) return target

        val parent = target.absoluteFile.parentFile
            ?: throw IOException("Model cache has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create model cache directory")
        val pending = File.createTempFile("model-", ".pending", parent)
        try {
            openAsset().use { input ->
                FileOutputStream(pending).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            if (pending.length() == 0L || !pending.inputStream().use(::digest).contentEquals(expected)) {
                throw IOException("Model asset changed or was truncated while copying")
            }
            // Same-directory atomic rename preserves the previous model on copy failure.
            Files.move(pending.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            pending.delete()
        }
    }

    private fun digest(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private const val BUFFER_SIZE = 64 * 1024
}
