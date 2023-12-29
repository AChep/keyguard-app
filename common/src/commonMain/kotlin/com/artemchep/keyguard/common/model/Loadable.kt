package com.artemchep.keyguard.common.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface Loadable<out T> {
    companion object;

    @Immutable
    data object Loading : Loadable<Nothing>

    @Immutable
    data class Ok<out T>(
        val value: T,
    ) : Loadable<T> {
        companion object
    }
}

fun <T> Loadable<T>.getOrNull(): T? = fold(
    ifLoading = { null },
    ifOk = { it },
)

fun <T, R> Loadable<T>.map(
    transform: (T) -> R,
) = flatMap {
    Loadable.Ok(transform(it))
}

inline fun <T, R> Loadable<T>.flatMap(
    transform: (T) -> Loadable<R>,
) = fold(
    ifLoading = { Loadable.Loading },
    ifOk = transform,
)

inline fun <T, R> Loadable<T>.fold(
    ifLoading: () -> R,
    ifOk: (T) -> R,
): R = when (this) {
    is Loadable.Loading -> ifLoading()
    is Loadable.Ok -> ifOk(value)
}
