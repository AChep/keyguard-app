package com.artemchep.keyguard.common.model

import arrow.core.Either

sealed interface PureYubiKeyAuthPrompt

data class YubiKeyAuthPrompt(
    val slot: Int,
    val challenge: ByteArray,
    val onComplete: (Either<Throwable, ByteArray>) -> Unit,
) : PureYubiKeyAuthPrompt
