package com.artemchep.keyguard.common.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class DCipherOpenedHistory(
    val cipherId: String,
    val instant: Instant = Clock.System.now(),
)
