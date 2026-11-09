package com.artemchep.keyguard.apple.applink

import com.artemchep.keyguard.apple.auth.KeyguardNetworkSnapshot

/** How this device reaches its linked apps; the same for every link. */
data class AppLinkConnectionSnapshot(
    /** `false` until the producer emits. */
    val loaded: Boolean,
    val network: KeyguardNetworkSnapshot,
    /** `false` while the settings are invalid, unchanged or being saved. */
    val canSave: Boolean,
    val isSaving: Boolean,
) {
    companion object {
        val empty = AppLinkConnectionSnapshot(
            loaded = false,
            network = KeyguardNetworkSnapshot.empty,
            canSave = false,
            isSaving = false,
        )
    }
}
