package com.artemchep.keyguard.feature.home.vault.model

import com.artemchep.keyguard.feature.favicon.FaviconUrl

/** Artwork metadata for renderers that cannot consume the Compose icon lambda. */
sealed interface VaultUriIcon {
    // Keep the source kind available for its placeholder when remote icons are off.
    val enabled: Boolean

    data class Website(
        val url: FaviconUrl,
        override val enabled: Boolean,
    ) : VaultUriIcon

    sealed interface App : VaultUriIcon {
        val identifier: String
    }

    data class IosApp(
        override val identifier: String,
        override val enabled: Boolean,
    ) : App

    data class AndroidApp(
        override val identifier: String,
        override val enabled: Boolean,
    ) : App
}
