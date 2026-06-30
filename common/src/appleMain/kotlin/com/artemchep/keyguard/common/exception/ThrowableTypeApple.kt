package com.artemchep.keyguard.common.exception

actual fun Throwable.isKeyException(): Boolean = false

actual fun Throwable.isOutOfMemoryError(): Boolean =
    this is OutOfMemoryError

actual fun Throwable.isProtocolException(): Boolean = false

actual fun Throwable.isSocketTimeoutException(): Boolean = false

actual fun Throwable.isUnknownHostException(): Boolean = false
