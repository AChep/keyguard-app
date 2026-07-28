package com.artemchep.keyguard.common.service.agent

import com.sun.jna.Platform
import kotlin.io.path.Path
import kotlin.io.path.isExecutable

/**
 * Attempts to locate the agent binary with the given base name
 * (without the platform-specific executable extension).
 */
internal fun findAgentBinary(
    binaryBaseName: String,
): java.nio.file.Path? {
    val appDirProp = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val appDirPath = Path(appDirProp)

    val binaryName = if (Platform.isWindows() || Platform.isWindowsCE()) {
        "$binaryBaseName.exe"
    } else {
        binaryBaseName
    }
    val binaryPath = appDirPath.resolve(binaryName)
    if (binaryPath.isExecutable()) {
        return binaryPath
    }
    return null
}
