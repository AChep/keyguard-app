package com.artemchep.keyguard.crypto

import com.sun.jna.Platform
import java.io.File

internal actual fun createPrivateTemporaryStorage(
): PrivateTemporaryStorage = if (Platform.isWindows()) {
    createWindowsPrivateTemporaryStorage()
} else {
    createPrivateTemporaryStorageJvm(directory = null)
}

internal actual fun createPrivateTemporaryFilePlatform(
    directory: File?,
): File = if (Platform.isWindows()) {
    createWindowsPrivateTemporaryFile(directory)
} else {
    createPrivateTemporaryFileJvm(directory)
}
