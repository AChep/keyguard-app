package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class DCipherOpenedHistory(
    val cipherId: String,
    val instant: Instant = Clock.System.now(),
)
