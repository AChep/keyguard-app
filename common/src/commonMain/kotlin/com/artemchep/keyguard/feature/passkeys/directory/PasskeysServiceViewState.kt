package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Immutable
import arrow.core.Either
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo

data class PasskeysServiceViewState(
    val content: Either<Throwable, Content>,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val model: PassKeyServiceInfo,
    ) {
        companion object
    }
}