package com.artemchep.keyguard.desktop.util

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.awt.Desktop
import java.io.IOException
import java.net.URI

fun navigateToBrowser(
    uri: String,
) {
    runCatching {
        Desktop.getDesktop().browse(URI(uri))
    }.onFailure { e ->
        if (e is IOException) {
            val platform = CurrentPlatform
            val handled = when (platform) {
                is Platform.Desktop.Windows -> {
                    Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", uri))
                    true
                }

                is Platform.Desktop.MacOS -> {
                    Runtime.getRuntime().exec(arrayOf("open", uri))
                    true
                }

                is Platform.Desktop.Linux -> {
                    Runtime.getRuntime().exec(arrayOf("xdg-open", uri))
                    true
                }

                // Not supported.
                else -> false
            }
            if (!handled) {
                throw e
            }
            return@onFailure
        }

        throw e
    }
}
