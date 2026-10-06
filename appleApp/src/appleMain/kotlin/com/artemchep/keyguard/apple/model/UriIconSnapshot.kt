package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon

enum class UriIconKind { WEBSITE, APP, LINK }

data class UriIconSnapshot(
    val kind: UriIconKind,
    val url: String?,
)

internal fun VaultUriIcon?.toUriIconSnapshot(
    appIcons: Map<VaultUriIcon.App, String?>,
): UriIconSnapshot = when (this) {
    is VaultUriIcon.Website -> UriIconSnapshot(
        kind = UriIconKind.WEBSITE,
        url = if (enabled) Favicon.getServerOrNull(url.serverId)?.transform(url.url) else null,
    )
    is VaultUriIcon.App -> UriIconSnapshot(
        kind = UriIconKind.APP,
        url = if (enabled) appIcons[this] else null,
    )
    null -> UriIconSnapshot(kind = UriIconKind.LINK, url = null)
}
