package com.artemchep.keyguard.feature.changekdf

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.masterkdf.MasterKdfFormState

/**
 * The state of the screen that changes the key derivation
 * of the vault.
 */
data class ChangeKdfState(
    /** The current app password. */
    val password: TextFieldModel,
    val kdf: MasterKdfFormState,
    val isLoading: Boolean = false,
    /**
     * Changes the key derivation; `null` while the input is not valid,
     * equals the current key derivation, or the screen is busy.
     */
    val onConfirm: (() -> Unit)? = null,
)
