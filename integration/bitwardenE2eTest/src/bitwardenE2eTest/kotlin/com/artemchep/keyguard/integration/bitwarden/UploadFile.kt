package com.artemchep.keyguard.integration.bitwarden

import java.nio.file.Files

/**
 * Runs [block] with the path of a temporary file holding [bytes], then deletes the file.
 * Keyguard's attachment and Send file uploads read the encrypted file from a path.
 */
internal suspend fun <T> withUploadFile(bytes: ByteArray, block: suspend (path: String) -> T): T {
    val file = Files.createTempFile("keyguard-e2e-upload", ".bin").toFile()
    try {
        file.writeBytes(bytes)
        return block(file.absolutePath)
    } finally {
        file.delete()
    }
}
