package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class AddCipherOpenedHistoryRequest(
    val accountId: String,
    val cipherId: String,
    val instant: Instant = Clock.System.now(),
)
