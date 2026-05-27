package com.artemchep.keyguard.desktop.util

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.awt.Desktop
import java.net.URI
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
            val platform = CurrentPlatform
            val handled = when (platform) {
                is Platform.Desktop.Windows -> {
                    windows(file.path)
                    true
                }

                is Platform.Desktop.MacOS -> {
                    macos(file.path)
                    true
                }

                is Platform.Desktop.Linux -> {
                    linux(file.path)
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

private fun linux(path: String) {
    val command = arrayOf("xdg-open", path)
    exec(command)
}

private fun windows(path: String) {
    val command = arrayOf("explorer.exe", "/select,", path)
    exec(command)
}

private fun macos(path: String) {
    val command = arrayOf("open", "-R", path)
    exec(command)
}

private fun exec(command: Array<String>) {
    Runtime.getRuntime().exec(command)
}
