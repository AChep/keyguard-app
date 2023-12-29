package com.artemchep.keyguard.common.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class AddCipherOpenedHistoryRequest(
    val accountId: String,
    val cipherId: String,
    val instant: Instant = Clock.System.now(),
)
