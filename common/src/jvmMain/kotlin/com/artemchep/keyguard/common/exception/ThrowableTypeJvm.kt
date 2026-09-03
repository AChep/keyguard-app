package com.artemchep.keyguard.common.exception

import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

actual fun Throwable.isOutOfMemoryError(): Boolean =
    this is OutOfMemoryError

actual fun Throwable.isProtocolException(): Boolean =
    this is ProtocolException

actual fun Throwable.isSocketTimeoutException(): Boolean =
    this is SocketTimeoutException

actual fun Throwable.isUnknownHostException(): Boolean =
    this is UnknownHostException
