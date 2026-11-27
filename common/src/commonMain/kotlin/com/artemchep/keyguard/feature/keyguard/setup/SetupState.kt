package com.artemchep.keyguard.feature.keyguard.setup

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.masterkdf.MasterKdfFormState
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
@optics
data class SetupState(
    val sideEffects: SideEffects = SideEffects(),
    val password: TextFieldModel,
    val crashlytics: SwitchFieldModel,
    val biometric: Biometric? = null,
    /** The key derivation of the app password. */
    val kdf: MasterKdfFormState,
    /** `true` if the key derivation section is shown. */
    val kdfExpanded: Boolean = false,
    /** Shows or hides the key derivation section. */
    val onKdfExpandedChange: (Boolean) -> Unit,
    /** A translated error, shown while the key derivation is not valid. */
    val kdfError: String? = null,
    val isLoading: Boolean = false,
    val actions: ImmutableList<ContextItem> = persistentListOf(),
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
