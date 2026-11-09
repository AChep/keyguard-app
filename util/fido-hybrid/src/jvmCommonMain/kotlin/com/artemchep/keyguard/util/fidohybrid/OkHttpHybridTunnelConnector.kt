@file:Suppress("MagicNumber") // FIDO routing ID, frame bounds, and connection timeouts.

package com.artemchep.keyguard.util.fidohybrid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class OkHttpHybridTunnelConnector : HybridTunnelConnector {
    override suspend fun connect(
        relay: HybridRelay,
        id: String,
        block: suspend (HybridTunnel) -> Unit,
    ) {
        val url = relay.newTunnelUrl(id)
        val opened = CompletableDeferred<ByteArray>()
        val closed = CompletableDeferred<Unit>()
        val messages = Channel<ByteArray>(8)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    val routing = response.header("X-Cable-Routing-Id")
                    val valid =
                        response.header(EXTENSIONS) == null &&
                            response.header("Sec-WebSocket-Protocol") == "fido.cable" &&
                            routing?.length == 6 && routing.all { it.digitToIntOrNull(16) != null }
                    if (!valid) {
                        opened.completeExceptionally(IllegalStateException("Invalid hybrid upgrade"))
                        webSocket.cancel()
                        return
                    }
                    opened.complete(requireNotNull(routing).hexToByteArray())
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    if (bytes.size > 65_536 || messages.trySend(bytes.toByteArray()).isFailure) {
                        messages.close(IllegalStateException("Hybrid input limit exceeded"))
                        webSocket.cancel()
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    messages.close(IllegalStateException("Expected hybrid binary frame"))
                    webSocket.cancel()
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    messages.close()
                    webSocket.close(code, null)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    // Avoid exposing a URL containing the tunnel identifier in errors or logs.
                    val error = IllegalStateException("Hybrid tunnel disconnected")
                    opened.completeExceptionally(error)
                    messages.close(error)
                    // Delivery of the queued response is unknown, so it is not a success.
                    closed.completeExceptionally(error)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    messages.close()
                    closed.complete(Unit)
                }
            }
        val request =
            Request
                .Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "fido.cable")
                .build()
        val socket = hybridClient.newWebSocket(request, listener)
        try {
            val routing = withTimeout(15_000) { opened.await() }
            block(
                object : HybridTunnel {
                    override val routingId = routing

                    override val incoming = messages

                    override suspend fun send(bytes: ByteArray) {
                        check(bytes.size <= 65_536 && socket.send(bytes.toByteString()))
                    }
                },
            )
            socket.close(1000, null)
            // OkHttp queues writes. Let it flush the assertion and finish the close handshake.
            withTimeout(5000) { closed.await() }
        } finally {
            socket.cancel()
            messages.cancel()
        }
    }
}

// Dedicated client: vault HTTP logging/interceptors must not observe QR material.
private val hybridClient by lazy {
    OkHttpClient
        .Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        // OkHttp offers permessage-deflate and inflates whole messages before the onMessage
        // size check, so a small compressed frame could exhaust memory. Never offer it.
        // WebSocket calls skip network interceptors; application interceptors see the offer.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader(EXTENSIONS).build())
        }.build()
}

private const val EXTENSIONS = "Sec-WebSocket-Extensions"
