package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State

data class WebDavSettingsState(
    // The URL must only be written through onUrlChange, which
    // also invalidates the remembered browse root.
    val url: State<String>,
    val username: MutableState<String>,
    val password: MutableState<String>,
    val error: Error?,
    val isTestingConnection: Boolean,
    val onUrlChange: (String) -> Unit,
    val onBrowse: () -> Unit,
    val onSave: () -> Unit,
    val onTestConnection: () -> Unit,
) {
    enum class Error {
        UrlRequired,
        InvalidUrl,
        FileUrlRequired,
        PasswordRequiresUsername,
    }
}
