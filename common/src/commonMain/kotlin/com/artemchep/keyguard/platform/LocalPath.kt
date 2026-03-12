package com.artemchep.keyguard.platform

import kotlinx.io.files.Path

@JvmInline
value class LocalPath(
    val value: String,
) {
    override fun toString(): String = value
}

fun LocalPath.resolve(
    vararg parts: String,
): LocalPath = LocalPath(
    value = Path(value, *parts).toString(),
)

fun LocalPath.toKotlinxIoPath(): Path = Path(value)
