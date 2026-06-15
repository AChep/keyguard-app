package com.artemchep.keyguard.feature.auth.companion

import java.io.File

private const val KEEPASS_COMPANION_ROOT_DIR_NAME = "keepass-companion"
private const val KEEPASS_COMPANION_DATABASE_FILE_NAME = "database.kdbx"

internal fun companionKeePassRootDir(
    filesDir: File,
): File = filesDir.resolve(KEEPASS_COMPANION_ROOT_DIR_NAME)

internal fun companionKeePassRequestDir(
    filesDir: File,
    requestId: String,
): File = companionKeePassRootDir(filesDir).resolve(requestId)

internal fun stageManagedCompanionKeePassDatabase(
    filesDir: File,
    requestId: String,
    sourceFile: File,
): File {
    val targetDir = companionKeePassRequestDir(
        filesDir = filesDir,
        requestId = requestId,
    )
    targetDir.deleteRecursively()
    targetDir.mkdirs()

    val targetFile = targetDir.resolve(KEEPASS_COMPANION_DATABASE_FILE_NAME)
    sourceFile.inputStream().use { input ->
        targetFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return targetFile
}

internal fun deleteManagedCompanionKeePassArtifacts(
    filesDir: File,
    requestId: String,
) {
    companionKeePassRequestDir(
        filesDir = filesDir,
        requestId = requestId,
    ).deleteRecursively()
}
