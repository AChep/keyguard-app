package com.artemchep.keyguard.desktop.util

import arrow.core.escaped
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import io.ktor.http.quote
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

fun navigateToFileInFileManager(
    uri: String,
) {
    val file = Paths.get(URI.create(uri)).toFile()
    runCatching {
        Desktop.getDesktop().browseFileDirectory(file)
    }.onFailure { e ->
        // Java has a very poor support of this functionality:
        // - Linux is not supported
        // - Windows 10 is not supported
        runCatching {
            val fileOrParent = if (!file.isDirectory) file.parentFile else file
            Desktop.getDesktop().open(fileOrParent)
        }.onFailure { e2 ->
            val escapedPath = file.path.escaped()

            val platform = CurrentPlatform
            val handled = when (platform) {
                is Platform.Desktop.Windows -> {
                    windows(escapedPath)
                    true
                }

                is Platform.Desktop.MacOS -> {
                    macos(escapedPath)
                    true
                }

                is Platform.Desktop.Linux -> {
                    linux(escapedPath)
                    true
                }

                // Not supported.
                else -> false
            }
            if (!handled) {
                throw e2
            }
            return@onFailure
        }
    }
}

private fun linux(escapedPath: String) {
    val command = "xdg-open '$escapedPath'"
    exec(command)
}

private fun windows(escapedPath: String) {
    val command = "explorer /select, '$escapedPath'"
    exec(command)
}

private fun macos(escapedPath: String) {
    val command = "open -R '$escapedPath'"
    exec(command)
}

private fun exec(command: String) {
    Runtime.getRuntime().exec(command)
}
