package com.artemchep.keyguard.feature.auth.common

sealed interface Validated<T> {
    val model: T

    data class Success<T>(
        override val model: T,
    ) : Validated<T>

    data class Failure<T>(
        override val model: T,
        val error: String = "",
    ) : Validated<T>
}

inline fun <T, reified Success : T> T.anyFlatMap(block: (Success) -> T) =
    when (this) {
        is Success -> block(this)
        else -> this
    }
