package com.artemchep.keyguard.feature.confirmation.elevatedaccess

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import kotlinx.coroutines.flow.Flow

@Immutable
data class ElevatedAccessState(
    val content: Loadable<Content> = Loadable.Loading,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val sideEffects: UnlockState.SideEffects,
        val password: TextFieldModel2,
        val biometric: Biometric? = null,
        val isLoading: Boolean = false,
    )

    @Immutable
    @optics
    data class Biometric(
        val onClick: (() -> Unit)? = null,
    ) {
        companion object
    }

    @Immutable
    @optics
    data class SideEffects(
        val showBiometricPromptFlow: Flow<BiometricAuthPrompt>,
    ) {
        companion object
    }
}
