package com.artemchep.keyguard.copy

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import com.artemchep.keyguard.util.io.toNSURL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

// 0700
private const val PRIVATE_DIRECTORY_PERMISSIONS = 448

// 0600
internal const val PRIVATE_FILE_PERMISSIONS = 384

/** Writes the export into a private temporary file, hands the file to [block], then deletes it. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun <T> withPrivateExportFile(
    fileName: String,
    write: suspend (Sink) -> Unit,
    block: suspend (NSURL) -> T,
): T {
    val tempDir = LocalPath(NSTemporaryDirectory())
        .resolve("keyguard-${NSUUID().UUIDString}")
    val tempFile = tempDir.resolve(fileName.sanitizedExportFileName())
    try {
        createPrivateExportFile(tempDir.value, tempFile.value)
        SystemFileSystem.sink(tempFile.toKotlinxIoPath())
            .buffered()
            .use { sink ->
                write(sink)
            }
        return block(tempFile.toNSURL())
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(
            path = tempDir.value,
            error = null,
        )
    }
}

/** Exported vault data must not become readable by other local users. */
@OptIn(ExperimentalForeignApi::class)
private fun createPrivateExportFile(directory: String, file: String) {
    val manager = NSFileManager.defaultManager
    check(manager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = mapOf(NSFilePosixPermissions to PRIVATE_DIRECTORY_PERMISSIONS),
        error = null,
    )) { "Could not create private export directory." }
    check(manager.createFileAtPath(
        file,
        contents = null,
        attributes = mapOf(NSFilePosixPermissions to PRIVATE_FILE_PERMISSIONS),
    )) { "Could not create private export file." }
}
