package com.artemchep.keyguard.feature.passwordleak

import androidx.compose.runtime.Immutable
import arrow.core.Either

@Immutable
data class PasswordLeakState(
    val content: Either<Throwable, Content>,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val occurrences: Int,
    )
}
