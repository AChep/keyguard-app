package com.artemchep.keyguard.common.util

import kotlinx.datetime.Instant

val Instant.millis get() = toEpochMilliseconds()

// See:
// https://github.com/dani-garcia/vaultwarden/pull/4386#issuecomment-2614321170
fun Instant.isOver6DigitsNanosOfSecond(): Boolean =
    nanosecondsOfSecond != convertNanosOfSecondTo6Digits(nanosecondsOfSecond)

// See:
// https://github.com/dani-garcia/vaultwarden/pull/4386#issuecomment-2614321170
fun Instant.to6DigitsNanosOfSecond(): Instant {
    val nanos = convertNanosOfSecondTo6Digits(nanosecondsOfSecond)
    return Instant.fromEpochSeconds(epochSeconds, nanos)
}

private fun convertNanosOfSecondTo6Digits(nanos: Int): Int {
    val multiplier = 1000
    return nanos
        .div(multiplier)
        .times(multiplier)
}
