package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath
import java.io.File

internal fun createPrivateTemporarySiblingJvm(
    destination: LocalPath,
): LocalPath {
    val destinationFile = File(destination.value).absoluteFile
    val directory = destinationFile.parentFile
        ?: error("Private atomic output requires a parent directory")
    check(directory.isDirectory || directory.mkdirs()) {
        "Could not create private atomic output directory"
    }
    val temporary = createPrivateTemporaryFile(directory)
    return LocalPath(temporary.absolutePath)
}
