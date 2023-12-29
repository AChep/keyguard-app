package com.artemchep.keyguard.desktop.util

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import io.ktor.http.quote
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
            val uriInQuotes = uri
                .quote()

            val platform = CurrentPlatform
            val handled = when (platform) {
                is Platform.Desktop.Windows -> {
                    Runtime.getRuntime().exec("start $uriInQuotes")
                    true
                }

                is Platform.Desktop.MacOS -> {
                    Runtime.getRuntime().exec("open $uriInQuotes")
                    true
                }

                is Platform.Desktop.Linux -> {
                    Runtime.getRuntime().exec("xdg-open $uriInQuotes")
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
