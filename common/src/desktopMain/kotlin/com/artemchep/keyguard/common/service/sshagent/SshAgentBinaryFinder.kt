package com.artemchep.keyguard.common.service.sshagent

import com.sun.jna.Platform
import kotlin.io.path.Path
import kotlin.io.path.isExecutable

private const val BINARY_FILE_NAME_UNIX = "keyguard-ssh-agent"
private const val BINARY_FILE_NAME_WINDOWS = "keyguard-ssh-agent.exe"

private val binaryFileName: String
    get() = if (Platform.isWindows() || Platform.isWindowsCE()) {
        BINARY_FILE_NAME_WINDOWS
    } else {
        BINARY_FILE_NAME_UNIX
    }

/**
 * Attempts to locate the keyguard-ssh-agent binary.
 */
internal fun findSshAgentBinary(): java.nio.file.Path? {
    val appDirProp = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val appDirPath = Path(appDirProp)

    val binaryName = binaryFileName
    val binaryPath = appDirPath.resolve(binaryName)
    if (binaryPath.isExecutable()) {
        return binaryPath
    }
    return null
}
