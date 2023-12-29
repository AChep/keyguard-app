package com.artemchep.keyguard.common.service.keyvalue

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlin.time.Duration

private const val NONE_INSTANT = -1L

private const val NONE_DURATION = -1L

interface KeyValuePreference<T> : Flow<T> {
    fun setAndCommit(value: T): IO<Unit>

    fun deleteAndCommit(): IO<Unit>
}

// Instant

fun KeyValuePreference<Long>.setAndCommit(instant: Instant?) = io(instant)
    .map { inst ->
        inst?.toEpochMilliseconds()
            ?: NONE_INSTANT
    }
    .flatMap(this::setAndCommit)

fun KeyValuePreference<Long>.asInstant() = this
    .map { millis ->
        millis.takeUnless { it == NONE_INSTANT }
            ?.let(Instant::fromEpochMilliseconds)
    }

// Duration

fun KeyValuePreference<Long>.setAndCommit(duration: Duration?) = io(duration)
    .map { dur ->
        dur?.inWholeMilliseconds
            ?: NONE_DURATION
    }
    .flatMap(this::setAndCommit)

fun KeyValuePreference<Long>.asDuration() = this
    .map { millis ->
        with(Duration) {
            millis.takeUnless { it == NONE_DURATION }
                ?.milliseconds
        }
    }
