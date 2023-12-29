package com.artemchep.keyguard.common.util

import kotlinx.datetime.Instant

val Instant.millis get() = toEpochMilliseconds()
