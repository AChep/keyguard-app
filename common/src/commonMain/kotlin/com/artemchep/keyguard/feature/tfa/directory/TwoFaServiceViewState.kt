package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Immutable
import arrow.core.Either
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo

data class TwoFaServiceViewState(
    val content: Either<Throwable, Content>,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val model: TwoFaServiceInfo,
    ) {
        companion object
    }
}