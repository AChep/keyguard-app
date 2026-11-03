package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsOriginException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

fun HttpRequestRetryConfig.configureBitwardenHttpRetry(
    retryDelay: (suspend (Long) -> Unit)? = null,
) {
    maxRetries = 5
    retryIf { _, response ->
        response.status == HttpStatusCode.TooManyRequests ||
                response.status.value in 500..599
    }
    // Same as the default, except for a request that the client
    // must not send: it fails the same way every time.
    retryOnExceptionIf { _, cause ->
        when (cause) {
            is MutualTlsOriginException,
            is HttpRequestTimeoutException,
            is ConnectTimeoutException,
            is SocketTimeoutException,
            is CancellationException,
            -> false

            else -> true
        }
    }
    constantDelay(
        respectRetryAfterHeader = true,
    )
    if (retryDelay != null) {
        delay(retryDelay)
    }
}
