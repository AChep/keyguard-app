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

    data class IosApp(
        val identifier: String,
        override val enabled: Boolean,
    ) : VaultUriIcon

    data class AndroidApp(
        val identifier: String,
        override val enabled: Boolean,
    ) : VaultUriIcon
}
