package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.defaultUserAgent

data class BitwardenPersona(
    val clientId: String,
    val clientName: String,
    val clientVersion: String,
    val deviceType: String,
    val deviceName: String,
    val userAgent: String,
    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Sec-CH-UA-Mobile
    val chUaMobile: String,
    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Sec-CH-UA-Platform
    val chUaPlatform: String,
) {
    companion object {
        const val CLIENT_VERSION = "2025.9.1"

        fun of(platform: Platform) = when (platform) {
            is Platform.Mobile -> {
                Platform.Desktop.Linux.bitwardenPersona()
            }

            is Platform.Desktop -> when (platform) {
                is Platform.Desktop.Windows -> platform.bitwardenPersona()
                is Platform.Desktop.MacOS -> platform.bitwardenPersona()
                is Platform.Desktop.Other,
                is Platform.Desktop.Linux,
                -> Platform.Desktop.Linux.bitwardenPersona()
            }
        }

        private fun Platform.Desktop.Linux.bitwardenPersona(
        ) = BitwardenPersona(
            clientId = "desktop",
            clientName = "desktop",
            clientVersion = CLIENT_VERSION,
            deviceType = "8",
            deviceName = "linux",
            userAgent = defaultUserAgent,
            chUaMobile = "?0",
            chUaPlatform = "Linux",
        )

        private fun Platform.Desktop.MacOS.bitwardenPersona(
        ) = BitwardenPersona(
            clientId = "desktop",
            clientName = "desktop",
            clientVersion = CLIENT_VERSION,
            deviceType = "7",
            deviceName = "macos",
            userAgent = defaultUserAgent,
            chUaMobile = "?0",
            chUaPlatform = "macOS",
        )

        private fun Platform.Desktop.Windows.bitwardenPersona(
        ) = BitwardenPersona(
            clientId = "desktop",
            clientName = "desktop",
            clientVersion = CLIENT_VERSION,
            deviceType = "6",
            deviceName = "windows",
            userAgent = defaultUserAgent,
            chUaMobile = "?0",
            chUaPlatform = "Windows",
        )
    }
}