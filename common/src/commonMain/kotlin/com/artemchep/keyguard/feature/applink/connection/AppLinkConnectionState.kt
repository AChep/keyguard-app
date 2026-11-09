package com.artemchep.keyguard.feature.applink.connection

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardNetworkState

/** How this device reaches its linked apps; the same for every link. */
@Immutable
data class AppLinkConnectionState(
    val network: KeyguardNetworkState,
    /** Saves the settings and closes the screen; `null` while they are invalid, unchanged or being saved. */
    val onSave: (() -> Unit)?,
    val isSaving: Boolean,
)
