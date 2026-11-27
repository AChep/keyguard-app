package com.artemchep.keyguard.feature.masterkdf

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

/**
 * The state of the key derivation (Argon2id) form, shared by
 * the vault setup and the key derivation settings.
 */
@Immutable
data class MasterKdfFormState(
    val iterations: TextFieldModel,
    /** The memory, in MiB. */
    val memory: TextFieldModel,
    val parallelism: TextFieldModel,
    /** Restores the default key derivation; `null` if it is the default. */
    val onReset: (() -> Unit)?,
    val test: Test,
    /** A translated warning about the entered values, if any. */
    val warning: String?,
    /** The entered key derivation; `null` if any field is not valid. */
    val kdf: MasterKdf.Argon2id?,
) {
    @Immutable
    data class Test(
        val isRunning: Boolean = false,
        /** How long the derivation took with the entered values, if tested. */
        val resultText: String? = null,
        /** `null` while the form is not valid, a test runs, or the screen is busy. */
        val onTest: (() -> Unit)? = null,
    )
}
