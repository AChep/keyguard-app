package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

@Immutable
@optics
data class UnlockState(
    val sideEffects: SideEffects,
    val password: TextFieldModel2,
    val biometric: Biometric? = null,
    val lockReason: String? = null,
    val isLoading: Boolean = false,
    val actions: ImmutableList<ContextItem> = persistentListOf(),
    val unlockVaultByMasterPassword: (() -> Unit)? = null,
) {
    companion object

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
        val showBiometricPromptFlow: Flow<PureBiometricAuthPrompt>,
    ) {
        companion object
    }
}
