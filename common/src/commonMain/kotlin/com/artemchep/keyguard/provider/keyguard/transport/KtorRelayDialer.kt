package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens ktor WebSockets to `<relay>/v1/ws` of [endpoint], with its token; [httpClient]
 * must have them installed. A WebSocket ends with a close frame, so the relay frees its
 * seats in the rooms at once, even when this device gave up on a connection the relay
 * still sees.
 */
internal class KtorRelayDialer(
    private val httpClient: HttpClient,
    private val endpoint: RelayEndpoint,
) : RelayDialer {
    override suspend fun dial(session: suspend (RelayWire) -> RelayError?): RelayError? = httpClient
        .prepareRequest {
            method = HttpMethod.Get
            url.takeFrom(endpoint.webSocketUrl)
            relayRequest(endpoint.token)
        }
        .execute { response ->
            when (response.status) {
                HttpStatusCode.SwitchingProtocols -> {
                    val webSocket = response.body<DefaultClientWebSocketSession>()
                    try {
                        session(KtorRelayWire(webSocket))
                    } finally {
                        withContext(NonCancellable) {
                            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                                runCatchingUnlessCancelled { webSocket.close(NORMAL_CLOSURE) }
                            }
                        }
                        webSocket.cancel()
                    }
                }

                HttpStatusCode.Unauthorized -> RelayError.Unauthorized
                HttpStatusCode.TooManyRequests -> RelayError.TooManyConnections
                else -> RelayError.Http(response.status.value)
            }
        }
}

/**
 * Sets up a request to a relay: its answer is read by status, never thrown, it is never
 * retried, and it carries the [token], if any, as `Authorization: Bearer <token>`.
 */
internal fun HttpRequestBuilder.relayRequest(token: String?) {
    expectSuccess = false
    retry { noRetry() }
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
}

/** How long the close frame of a WebSocket may take to leave. */
private const val CLOSE_TIMEOUT_MS = 1_000L

private val NORMAL_CLOSURE = CloseReason(CloseReason.Codes.NORMAL, "")

private class KtorRelayWire(
    private val webSocket: DefaultClientWebSocketSession,
) : RelayWire {
    override val incoming: ReceiveChannel<Frame> get() = webSocket.incoming

    override suspend fun send(frame: Frame) = webSocket.send(frame)

    override suspend fun closeReason(): CloseReason? = webSocket.closeReason.await()
}
