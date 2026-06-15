package com.artemchep.keyguard.common.exception

import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyException

actual fun Throwable.isKeyException(): Boolean =
    this is KeyException

actual fun Throwable.isOutOfMemoryError(): Boolean =
    this is OutOfMemoryError

actual fun Throwable.isProtocolException(): Boolean =
    this is ProtocolException

actual fun Throwable.isSocketTimeoutException(): Boolean =
    this is SocketTimeoutException

actual fun Throwable.isUnknownHostException(): Boolean =
    this is UnknownHostException
