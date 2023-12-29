package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant
import kotlin.time.Duration

data class TotpCode(
    val code: String,
    val counter: Counter,
) {
    sealed interface Counter

    data class TimeBasedCounter(
        val timestamp: Instant,
        val expiration: Instant,
        val duration: Duration,
    ) : Counter

    data class IncrementBasedCounter(
        val counter: Long,
    ) : Counter
}
