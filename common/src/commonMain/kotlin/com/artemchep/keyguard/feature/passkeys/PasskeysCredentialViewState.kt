package com.artemchep.keyguard.feature.passkeys

import androidx.compose.runtime.Immutable
import arrow.core.Either
import com.artemchep.keyguard.common.model.DSecret

data class PasskeysCredentialViewState(
    val content: Either<Throwable, Content>,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val model: DSecret.Login.Fido2Credentials,
        val createdAt: String,
        val onUse: (() -> Unit)? = null,
    ) {
        companion object
    }
}