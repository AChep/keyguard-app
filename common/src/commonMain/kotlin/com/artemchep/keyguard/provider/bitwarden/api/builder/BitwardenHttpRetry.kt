package com.artemchep.keyguard.provider.bitwarden.api.builder

import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.http.HttpStatusCode

internal fun HttpRequestRetryConfig.configureBitwardenHttpRetry(
    retryDelay: (suspend (Long) -> Unit)? = null,
) {
    maxRetries = 5
    retryIf { _, response ->
        response.status == HttpStatusCode.TooManyRequests ||
                response.status.value in 500..599
    }
    constantDelay(
        respectRetryAfterHeader = true,
    )
    if (retryDelay != null) {
        delay(retryDelay)
    }
}
