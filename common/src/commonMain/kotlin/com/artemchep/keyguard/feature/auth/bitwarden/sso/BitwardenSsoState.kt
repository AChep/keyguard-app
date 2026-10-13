package com.artemchep.keyguard.feature.auth.bitwarden.sso

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

@Immutable
data class BitwardenSsoState(
    /** The browser sign-in to present; `null` once it has finished. */
    val browser: SsoBrowserPrompt? = null,
    /** The master password field; `null` until the browser sign-in succeeds, and for Key Connector. */
    val password: TextFieldModel? = null,
    val isLoading: Boolean = false,
    val onUnlock: (() -> Unit)? = null,
    /** Starts a new browser sign-in, replacing a running one; `null` once the browser sign-in has succeeded. */
    val onRetry: (() -> Unit)? = null,
)
