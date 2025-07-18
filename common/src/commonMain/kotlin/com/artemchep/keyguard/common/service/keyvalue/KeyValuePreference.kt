package com.artemchep.keyguard.common.service.keyvalue

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration

private const val NONE_INSTANT = -1L

private const val NONE_DURATION = -1L

sealed interface KeyValuePreference<T> : Flow<T> {
    val key: String

    fun setAndCommit(value: T): IO<Unit>

    fun deleteAndCommit(): IO<Unit>
}

interface RealKeyValuePreference<A> : KeyValuePreference<A> {
    /**
     * A class of the backing field used
     * to store the data.
     */
    val clazz: KClass<*>
}


interface VirtualKeyValuePreference<A, B> : KeyValuePreference<A> {
    val field: KeyValuePreference<B>
}

/**
 * Try to find the closest real key-value
 * preference implementation.
 */
fun <T> KeyValuePreference<T>.findRealKeyValuePreferenceOrNull(
): RealKeyValuePreference<*>? = when (this) {
    is RealKeyValuePreference<T> -> this
    is VirtualKeyValuePreference<*, *> -> field
        .findRealKeyValuePreferenceOrNull()
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
