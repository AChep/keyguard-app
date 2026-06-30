package com.artemchep.keyguard.feature.changepassword

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ChangePasswordState(
    val sideEffects: SideEffects = SideEffects(),
    val password: Password,
    val biometric: Biometric?,
    val isLoading: Boolean = false,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    @optics
    data class Password(
        val current: TextFieldModel,
        val new: TextFieldModel,
    ) {
        companion object
    }

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
