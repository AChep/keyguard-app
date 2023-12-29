package com.artemchep.keyguard.feature.keyguard.setup

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
@optics
data class SetupState(
    val sideEffects: SideEffects = SideEffects(),
    val password: TextFieldModel2,
    val crashlytics: SwitchFieldModel,
    val biometric: Biometric? = null,
    val isLoading: Boolean = false,
    val onCreateVault: (() -> Unit)? = null,
) {
    companion object;

    @Immutable
    @optics
    data class Biometric(
        val checked: Boolean = false,
        val onChange: ((Boolean) -> Unit)? = null,
    ) {
        companion object
    }

    @Immutable
    @optics
    data class SideEffects(
        val showBiometricPromptFlow: Flow<BiometricAuthPrompt> = emptyFlow(),
    ) {
        companion object
    }
}
