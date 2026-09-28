package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.common.model.PureYubiKeyAuthPrompt
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.artemchep.keyguard.feature.fido2.Fido2Prompt

@Immutable
@optics
data class UnlockState(
    val sideEffects: SideEffects,
    val password: TextFieldModel,
    val biometric: Biometric? = null,
    val yubiKey: YubiKey? = null,
    val fido2: Fido2? = null,
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
    data class YubiKey(
        val onClick: (() -> Unit)? = null,
    ) {
        companion object
    }

    @Immutable
    data class Fido2(val onClick: (() -> Unit)? = null)

    @Immutable
    @optics
    data class SideEffects(
        val showBiometricPromptFlow: Flow<PureBiometricAuthPrompt>,
        val showYubiKeyPromptFlow: Flow<PureYubiKeyAuthPrompt>,
        val showFido2PromptFlow: Flow<Fido2Prompt> = emptyFlow(),
    ) {
        companion object
    }
}
