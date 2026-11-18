package com.artemchep.keyguard.common.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Retention periods for local history, shared by the settings pickers. */
object HistoryRetention {
    /** A month in the retention options; the labels show it in months. */
    val MONTH = 30.days

    val variants: List<Duration> = listOf(
        1.days,
        7.days,
        14.days,
        MONTH,
        MONTH * 6,
        Duration.INFINITE,
    )
}
