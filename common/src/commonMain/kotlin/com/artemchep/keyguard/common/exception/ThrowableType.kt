package com.artemchep.keyguard.common.exception

import kotlinx.io.IOException

fun Throwable.isIoException(): Boolean =
    this is IOException

expect fun Throwable.isKeyException(): Boolean

expect fun Throwable.isOutOfMemoryError(): Boolean

expect fun Throwable.isProtocolException(): Boolean

expect fun Throwable.isSocketTimeoutException(): Boolean

expect fun Throwable.isUnknownHostException(): Boolean
