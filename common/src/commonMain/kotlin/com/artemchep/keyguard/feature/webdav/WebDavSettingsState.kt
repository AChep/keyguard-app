package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.MutableState

data class WebDavSettingsState(
    val url: MutableState<String>,
    val username: MutableState<String>,
    val password: MutableState<String>,
    val error: Error?,
    val isTestingConnection: Boolean,
    val onSave: () -> Unit,
    val onTestConnection: () -> Unit,
) {
    enum class Error {
        UrlRequired,
        PasswordRequiresUsername,
    }
}
