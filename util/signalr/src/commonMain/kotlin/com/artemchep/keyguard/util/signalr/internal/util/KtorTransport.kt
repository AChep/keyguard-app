package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.HubConnectionHttpException
import com.artemchep.keyguard.util.signalr.internal.Transport
import com.artemchep.keyguard.util.signalr.internal.transports.WebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.takeFrom
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val HTTP = "http:"
private const val HTTPS = "https:"
private const val WS = "ws:"
private const val WSS = "wss:"

internal typealias WebSocketSessionConnector = suspend (
    httpClient: HttpClient,
    url: String,
    headers: Map<String, String>,
) -> WebSocketSession

internal val DefaultWebSocketSessionConnector: WebSocketSessionConnector = { httpClient, url, headers ->
    httpClient.openWebSocketSession(
        urlString = url,
        headers = headers,
    )
}

private suspend fun HttpClient.openWebSocketSession(
    urlString: String,
    headers: Map<String, String>,
): WebSocketSession {
    plugin(WebSockets)
    val sessionDeferred = CompletableDeferred<DefaultClientWebSocketSession>()
    val statement = prepareRequest {
        expectSuccess = false
        url.takeFrom(urlString)
        headers {
            headers.forEach { (key, value) ->
                append(key, value)
            }
        }

        timeout {
            requestTimeoutMillis = Long.MAX_VALUE
        }
    }

    val upgradeJob = launch {
        try {
            statement.execute { response ->
                val status = response.status
                if (status != HttpStatusCode.SwitchingProtocols) {
                    throw HubConnectionHttpException(
                        statusCode = status,
                        message = "Unexpected status code returned from WebSocket upgrade: $status ${status.description}.",
                    )
                }

                val session = response.body<DefaultClientWebSocketSession>()
                val sessionCompleted = CompletableDeferred<Unit>()
                sessionDeferred.complete(session)
                session.outgoing.invokeOnClose { cause ->
                    if (cause != null) {
                        sessionCompleted.completeExceptionally(cause)
                    } else {
                        sessionCompleted.complete(Unit)
                    }
                }
                sessionCompleted.await()
            }
        } catch (cause: Throwable) {
            sessionDeferred.completeExceptionally(cause)
        }
    }

    return try {
        sessionDeferred.await()
    } catch (cause: CancellationException) {
        upgradeJob.cancel(cause)
        throw cause
    }
}

internal suspend fun HttpClient.connectTransport(
    url: String,
    headers: Map<String, String>,
    webSocketSessionConnector: WebSocketSessionConnector = DefaultWebSocketSessionConnector,
): Transport {
    val session = webSocketSessionConnector(this, autoFixWebSocketUrl(url), headers)
    session.ensureActive()
    return WebSocketTransport(session)
}

private fun autoFixWebSocketUrl(
    url: String,
): String = when {
    url.startsWith(HTTPS, ignoreCase = true) -> WSS + url.substring(HTTPS.length)
    url.startsWith(HTTP, ignoreCase = true) -> WS + url.substring(HTTP.length)
    else -> url
}
