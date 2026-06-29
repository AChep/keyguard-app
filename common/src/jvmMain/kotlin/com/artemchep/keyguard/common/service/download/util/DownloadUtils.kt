package com.artemchep.keyguard.common.service.download.util

import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.crypto.FileEncryptorJvm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal suspend fun File.copyDecryptedToLocalFileAfterVerification(
    dst: File,
    key: ByteArray?,
    fileEncryptor: FileEncryptor,
) = withContext(Dispatchers.IO) {
    val src = this@copyDecryptedToLocalFileAfterVerification
    if (src == dst && key == null) {
        return@withContext
    }

    dst.parentFile?.mkdirs()
    val tmp = createTempFileNear(dst)
    try {
        tmp.outputStream().use { output ->
            src.copyDecryptedTo(
                key = key,
                fileEncryptor = fileEncryptor,
                output = output,
            )
        }
        tmp.replaceWith(dst)
    } catch (e: Throwable) {
        runCatching {
            tmp.delete()
        }
        throw e
    }
}

internal suspend fun File.copyDecryptedToSinkAfterVerification(
    key: ByteArray?,
    fileEncryptor: FileEncryptor,
    output: OutputStream,
) = withContext(Dispatchers.IO) {
    val src = this@copyDecryptedToSinkAfterVerification
    if (key == null) {
        src.copyDecryptedTo(
            key = key,
            fileEncryptor = fileEncryptor,
            output = output,
        )
        return@withContext
    }

    val tmp = createTempFileNear(src)
    try {
        tmp.outputStream().use { tmpOutput ->
            src.copyDecryptedTo(
                key = key,
                fileEncryptor = fileEncryptor,
                output = tmpOutput,
            )
        }
        tmp.inputStream().use { tmpInput ->
            tmpInput.copyTo(output)
        }
    } finally {
        tmp.delete()
    }
}

private fun File.copyDecryptedTo(
    key: ByteArray?,
    fileEncryptor: FileEncryptor,
    output: OutputStream,
) {
    inputStream()
        .use { input ->
            if (key != null) {
                require(fileEncryptor is FileEncryptorJvm) {
                    "File encryptor must use a JVM implementation on " +
                            "the JVM platforms!"
                }
                fileEncryptor
                    .decode(
                        input = input,
                        key = key,
                    )
                    .use { decryptedInput ->
                        decryptedInput.copyTo(output)
                    }
            } else {
                input.copyTo(output)
            }
        }
}

private fun createTempFileNear(
    file: File,
): File {
    val dir = file.absoluteFile.parentFile
        ?: throw IOException("Can not create a temporary file for '${file.path}'.")
    dir.mkdirs()
    return File.createTempFile("download.", ".tmp", dir)
}

private fun File.replaceWith(
    dst: File,
) {
    if (dst.exists() && !dst.delete()) {
        throw IOException("Can not replace '${dst.path}'.")
    }

    if (renameTo(dst)) {
        return
    }

    try {
        copyTo(dst, overwrite = true)
    } catch (e: Throwable) {
        runCatching {
            dst.delete()
        }
        throw e
    } finally {
        delete()
    }
}
