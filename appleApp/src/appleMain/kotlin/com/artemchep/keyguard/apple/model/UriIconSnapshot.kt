package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.feature.favicon.toIconKey
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon

enum class UriIconKind { WEBSITE, APP, LINK }

data class UriIconSnapshot(
    val kind: UriIconKind,
    /** Loaded with `KeyguardCore.loadIcon`; `null` shows the [kind] symbol. */
    val iconKey: String?,
)

internal fun VaultUriIcon?.toUriIconSnapshot(): UriIconSnapshot = when (this) {
    is VaultUriIcon.Website -> UriIconSnapshot(
        kind = UriIconKind.WEBSITE,
        iconKey = if (enabled) url.toIconKey() else null,
    )
    is VaultUriIcon.AndroidApp -> UriIconSnapshot(
        kind = UriIconKind.APP,
        iconKey = if (enabled) AppIconUrl(identifier).toIconKey() else null,
    )
    is VaultUriIcon.IosApp -> UriIconSnapshot(
        kind = UriIconKind.APP,
        iconKey = if (enabled) IosAppIconUrl(identifier).toIconKey() else null,
    )
    null -> UriIconSnapshot(kind = UriIconKind.LINK, iconKey = null)
}
