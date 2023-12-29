package com.artemchep.keyguard.ui

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CodeException(
    val code: Int,
    val description: String? = null,
) : IOException()

fun Throwable.getHttpCode(): Int {
    if (this is HttpException) {
        return this.statusCode.value
    }
    if (this is CodeException) {
        return this.code
    }
    if (this is UnknownHostException) {
        return BitwardenService.Error.CODE_UNKNOWN_HOST
    }
    if (this is ProtocolException) {
        return BitwardenService.Error.CODE_PROTOCOL_EXCEPTION
    }
    if (this is SocketTimeoutException) {
        return BitwardenService.Error.CODE_UNKNOWN_HOST
    }
    return cause?.getHttpCode()
        ?: BitwardenService.Error.CODE_UNKNOWN
}

fun Int.canRetry(): Boolean =
    this == BitwardenService.Error.CODE_UNKNOWN ||
            this == BitwardenService.Error.CODE_PROTOCOL_EXCEPTION ||
            this == BitwardenService.Error.CODE_UNKNOWN_HOST ||
            this == BitwardenService.Error.CODE_DECODING_FAILED ||
            // 400x
            this == HttpStatusCode.PaymentRequired.value ||
            this == HttpStatusCode.ProxyAuthenticationRequired.value ||
            this == HttpStatusCode.RequestTimeout.value ||
            // 500x
            this in 500..599
