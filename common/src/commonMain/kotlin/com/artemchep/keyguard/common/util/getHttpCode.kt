package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.exception.isProtocolException
import com.artemchep.keyguard.common.exception.isSocketTimeoutException
import com.artemchep.keyguard.common.exception.isUnknownHostException
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException

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
    if (isUnknownHostException()) {
        return BitwardenService.Error.CODE_UNKNOWN_HOST
    }
    if (isProtocolException()) {
        val regex = "^Expected HTTP \\d+ .* was '(\\d+).*'.*".toRegex()
        val code = this.message
            ?.let { msg ->
                val match = regex.matchEntire(msg)
                    ?: return@let null
                match.groupValues.getOrNull(1)?.toIntOrNull()
            }
        return code
            ?: BitwardenService.Error.CODE_PROTOCOL_EXCEPTION
    }
    if (isSocketTimeoutException()) {
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
            this == HttpStatusCode.TooManyRequests.value ||
            // 500x
            this in 500..599
