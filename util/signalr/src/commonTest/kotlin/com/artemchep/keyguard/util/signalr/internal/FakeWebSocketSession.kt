package com.artemchep.keyguard.util.signalr.internal

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * An in-memory [WebSocketSession] shared by the hub test suites. By default
 * it immediately delivers a successful handshake response; pass a `null`
 * [handshakePayload] for a session that stays silent.
 */
internal class FakeWebSocketSession(
    handshakePayload: ByteArray? = "{}$RECORD_SEPARATOR".encodeToByteArray(),
) : WebSocketSession {
    private val job = Job()
    private val incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = job
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming: ReceiveChannel<Frame> = incomingFrames
    override val outgoing: SendChannel<Frame> = outgoingFrames
    override val extensions: List<WebSocketExtension<*>> = emptyList()

    val connected = CompletableDeferred<String>()

    init {
        handshakePayload?.let { payload ->
            incomingFrames.trySend(Frame.Text(payload.decodeToString()))
        }
    }

    override suspend fun flush() = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    @Deprecated(
        "Use cancel() instead.",
        ReplaceWith("cancel()", "kotlinx.coroutines.cancel"),
        level = DeprecationLevel.ERROR,
    )
    override fun terminate() {
        dispose()
    }

    suspend fun awaitCloseFrame() {
        withTimeout(5.seconds) {
            while (true) {
                val frame = outgoingFrames.receive()
                if (frame is Frame.Close) {
                    return@withTimeout
                }
            }
        }
    }

    suspend fun awaitTextMessage(
        expected: String,
    ) {
        withTimeout(5.seconds) {
            while (true) {
                when (val frame = outgoingFrames.receive()) {
                    is Frame.Text -> {
                        if (frame.readText() == expected) {
                            return@withTimeout
                        }
                    }

                    is Frame.Binary -> error("Unexpected binary websocket frame: ${frame.readBytes().size} bytes.")
                    else -> Unit
                }
            }
        }
    }

    fun closeIncoming() {
        incomingFrames.close()
    }

    fun fail(
        cause: Throwable,
    ) {
        incomingFrames.close(cause)
    }

    fun failOutgoing(
        cause: Throwable,
    ) {
        outgoingFrames.close(cause)
    }

    fun receiveInvocation(
        target: String,
    ) {
        incomingFrames.trySend(
            Frame.Text(
                "{\"type\":1,\"target\":\"$target\",\"arguments\":[]}$RECORD_SEPARATOR",
            ),
        )
    }

    fun dispose() {
        incomingFrames.close()
        outgoingFrames.close()
        job.cancel()
    }
}
