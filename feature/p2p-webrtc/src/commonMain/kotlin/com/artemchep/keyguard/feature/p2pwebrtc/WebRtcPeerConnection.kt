package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.util.webrtc.WebRtcConnectionState
import com.artemchep.keyguard.util.webrtc.WebRtcEvent
import com.artemchep.keyguard.util.webrtc.WebRtcException
import com.artemchep.keyguard.util.webrtc.WebRtcPeer
import com.artemchep.keyguard.util.webrtc.WebRtcSdpType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Why a WebRTC connection could not be used. */
class WebRtcTransportException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception("webrtc_transport_" + reason.name.lowercase(), cause) {
    enum class Reason {
        /** The data channel did not open within the connect timeout. */
        TIMEOUT,

        /** ICE failed or the connection closed. */
        CLOSED,
    }
}

/**
 * One [WebRtcPeer] with at most one data channel, as [WebRtcTransport] drives it. It
 * collects the peer's events in a child of [scope] for its whole life; [close] ends both.
 *
 * The offerer calls [createChannel] before [createOffer]; the answerer reads [stream]
 * after [createAnswer]. [localCandidates] replays the latest candidates to a late collector.
 */
internal class WebRtcPeerConnection(
    private val peer: WebRtcPeer,
    scope: CoroutineScope,
    private val onPath: (TransportPath) -> Unit = {},
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])

    private val candidateSink = MutableSharedFlow<String>(
        replay = CANDIDATE_REPLAY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val inbox = Channel<ByteArray>(INBOX_CAPACITY)

    private val opened = CompletableDeferred<Unit>()

    /** The data channel; it opens later, see [awaitOpen]. */
    val stream = WebRtcByteStream(this, peer)

    /** The local ICE candidates to send to the peer, as opaque native JSON. */
    val localCandidates: SharedFlow<String> = candidateSink.asSharedFlow()

    init {
        CoroutineScope(scope.coroutineContext + job).launch { pump() }
    }

    /** Creates the SDP offer. */
    suspend fun createOffer(): String = peer.createOffer()

    /** Applies the peer's SDP [offer] and returns the answer. */
    suspend fun createAnswer(offer: String): String {
        peer.setRemoteDescription(WebRtcSdpType.OFFER, offer)
        return peer.createAnswer()
    }

    /** Applies the peer's SDP [answer]. */
    suspend fun setRemoteAnswer(answer: String) {
        peer.setRemoteDescription(WebRtcSdpType.ANSWER, answer)
    }

    /**
     * Adds a candidate of the peer, as it sent it from its [localCandidates]. The native
     * stack parses it, and throws [WebRtcException] for anything else.
     */
    suspend fun addIceCandidate(candidate: String) {
        peer.addIceCandidate(candidate)
    }

    /** Creates the data channel [label] on the offerer, before [createOffer]. */
    suspend fun createChannel(label: String) {
        peer.createChannel(label)
    }

    /** Suspends until the data channel opens; throws once the connection is closed. */
    suspend fun awaitOpen() {
        opened.await()
    }

    /** Closes the connection and its channel. */
    suspend fun close() {
        try {
            peer.close()
        } catch (expected: WebRtcException) {
            // Already gone with its runtime.
        }
        finish(null)
        job.cancel()
    }

    private suspend fun pump() {
        var cause: Throwable? = null
        try {
            peer.events().collect(::dispatch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebRtcException) {
            cause = e
        } finally {
            finish(cause)
        }
    }

    private suspend fun dispatch(event: WebRtcEvent) {
        when (event) {
            is WebRtcEvent.IceCandidate -> candidateSink.emit(event.json)
            is WebRtcEvent.ConnectionState -> onState(event.state)
            WebRtcEvent.ChannelOpen -> opened.complete(Unit)
            is WebRtcEvent.ChannelMessage -> try {
                inbox.send(event.data)
            } catch (expected: ClosedSendChannelException) {
                // Closed by the reader; the native peer is closing too.
            }
            WebRtcEvent.ChannelClose -> finish(null)
            is WebRtcEvent.SelectedPair -> {
                val path = TransportPath.of(event.pair.localType, event.pair.remoteType)
                // A failing observer must not end the connection.
                runCatching { onPath(path) }
            }

            is WebRtcEvent.Error -> if (event.code == WebRtcEvent.Error.CODE_CHANNEL) finish(null)
            // The peer wakes its own waiting writers.
            WebRtcEvent.BufferedLow,
            WebRtcEvent.IceGatheringComplete,
            -> Unit
        }
    }

    private fun onState(state: WebRtcConnectionState) {
        // A disconnected connection may recover; the session's heartbeat decides.
        if (state == WebRtcConnectionState.FAILED || state == WebRtcConnectionState.CLOSED) {
            finish(null)
        }
    }

    /** Ends the stream: pending reads drain and then return `null`, writes throw. */
    private fun finish(cause: Throwable?) {
        opened.completeExceptionally(
            WebRtcTransportException(WebRtcTransportException.Reason.CLOSED, cause),
        )
        inbox.close()
    }

    private companion object {
        /** Enough for every candidate of a gathering round. */
        const val CANDIDATE_REPLAY = 64

        /** Messages held for a slow reader before the native queue fills up. */
        const val INBOX_CAPACITY = 64
    }
}

/**
 * The ordered, reliable data channel of a [WebRtcPeerConnection] as a [ByteStream].
 * Messages are at most [MAX_MESSAGE_SIZE] bytes.
 */
class WebRtcByteStream internal constructor(
    private val connection: WebRtcPeerConnection,
    private val peer: WebRtcPeer,
) : ByteStream {
    companion object {
        /** The largest message of a data channel. */
        const val MAX_MESSAGE_SIZE: Int = 64 * 1024

        /** [awaitWritable] waits while more than this is buffered. */
        private const val HIGH_WATER_BYTES: Long = 512L * 1024L
    }

    override suspend fun read(): ByteArray? = connection.inbox.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_MESSAGE_SIZE) { "A message is at most $MAX_MESSAGE_SIZE bytes" }
        connection.awaitOpen()
        try {
            peer.send(bytes)
        } catch (e: WebRtcException) {
            throw WebRtcTransportException(WebRtcTransportException.Reason.CLOSED, e)
        }
    }

    override suspend fun awaitWritable() {
        connection.awaitOpen()
        try {
            peer.awaitBufferedAtMost(HIGH_WATER_BYTES)
        } catch (e: WebRtcException) {
            throw WebRtcTransportException(WebRtcTransportException.Reason.CLOSED, e)
        }
    }

    override suspend fun close() {
        connection.close()
    }
}
