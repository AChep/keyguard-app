package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.internal.Transport
import com.artemchep.keyguard.util.signalr.internal.transports.WebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.headers
import kotlinx.coroutines.ensureActive

private const val HTTP = "http"
private const val HTTPS = "https"
private const val WS = "ws"
private const val WSS = "wss"

internal suspend fun HttpClient.connectTransport(
    url: String,
    headers: Map<String, String>,
): Transport {
    val session = webSocketSession(urlString = autoFixWebSocketUrl(url)) {
        headers {
            headers.forEach { (key, value) ->
                append(key, value)
            }
        }

        timeout {
            requestTimeoutMillis = Long.MAX_VALUE
        }
    }
    session.ensureActive()
    return WebSocketTransport(session)
}

private fun autoFixWebSocketUrl(
    url: String,
): String = when {
    url.startsWith(HTTPS) -> WSS + url.substring(HTTPS.length)
    url.startsWith(HTTP) -> WS + url.substring(HTTP.length)
    else -> url
}
