package com.artemchep.keyguard.common.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** How long a release must be public before the app tells the user about it. */
object AppUpdateDelay {
    val DEFAULT = 7.days

    val variants: List<Duration> = listOf(
        Duration.ZERO,
        1.days,
        3.days,
        7.days,
        14.days,
        30.days,
    )
}
