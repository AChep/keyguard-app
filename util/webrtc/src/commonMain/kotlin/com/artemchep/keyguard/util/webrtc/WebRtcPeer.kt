package com.artemchep.keyguard.util.webrtc

import com.artemchep.keyguard.util.ffi.runNativeOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long one native poll waits before the loop polls again. */
private const val EVENT_POLL_TIMEOUT_MS: Int = 10_000

/**
 * How long a waiting [WebRtcPeer.send] or [WebRtcPeer.awaitBufferedAtMost]
 * waits for [WebRtcEvent.BufferedLow] before it checks again anyway, for a
 * caller that does not collect the events.
 */
private const val BUFFERED_RECHECK_MS: Long = 250L

/**
 * One WebRTC peer connection with at most one ordered, reliable data channel.
 *
 * Signaling is the caller's: send the SDP from [createOffer] or
 * [createAnswer] and every [WebRtcEvent.IceCandidate] to the remote peer, and
 * apply theirs with [setRemoteDescription] and [addIceCandidate]. Candidates
 * may arrive before the remote description; they are held until it is set.
 *
 * Collect [events] exactly once, for the lifetime of the peer. Requests block
 * an IO thread for at most 10 seconds.
 */
class WebRtcPeer internal constructor(
    private val handle: Long,
) {
    /** Counts [WebRtcEvent.BufferedLow] events, and the close, for the waiting writers. */
    private val bufferedLowCount = MutableStateFlow(0L)

    /** Creates the data channel. Call it on the offerer, before [createOffer]. */
    suspend fun createChannel(label: String) {
        request { NativeWebrtc.peerCreateChannel(handle, label) }
    }

    /** Creates an offer, applies it locally and returns its SDP. */
    suspend fun createOffer(): String = description(handle, NativeWebrtc::peerCreateOffer)

    /** Creates an answer to the remote offer, applies it locally and returns its SDP. */
    suspend fun createAnswer(): String = description(handle, NativeWebrtc::peerCreateAnswer)

    /**
     * Applies the remote peer's offer or answer.
     *
     * @throws WebRtcException with [WebRtcFailure.INVALID_STATE] when its
     * inline candidates take the peer past 256 candidates.
     */
    suspend fun setRemoteDescription(type: WebRtcSdpType, sdp: String) {
        val kind = when (type) {
            WebRtcSdpType.OFFER -> NATIVE_WEBRTC_SDP_OFFER
            WebRtcSdpType.ANSWER -> NATIVE_WEBRTC_SDP_ANSWER
        }
        request { NativeWebrtc.peerSetRemote(handle, kind, sdp) }
    }

    /**
     * Adds a remote candidate, the [WebRtcEvent.IceCandidate] JSON of the
     * remote peer. Returns once it is parsed; a candidate the stack rejects
     * later is reported as [WebRtcEvent.Error].
     *
     * @throws WebRtcException with [WebRtcFailure.INVALID_ARGUMENT] for
     * anything but a candidate, [WebRtcFailure.INPUT_TOO_LONG] over 4 KiB, or
     * [WebRtcFailure.INVALID_STATE] past 256 candidates.
     */
    suspend fun addIceCandidate(json: String) {
        request { NativeWebrtc.peerAddIceCandidate(handle, json) }
    }

    /**
     * Sends one message of at most 64 KiB on the open channel. While more
     * than 1 MiB is buffered it suspends until the next
     * [WebRtcEvent.BufferedLow].
     */
    suspend fun send(data: ByteArray) {
        while (true) {
            val generation = bufferedLowCount.value
            val result = withContext(Dispatchers.IO) {
                NativeWebrtc.channelSend(handle, data, 0, data.size)
            }
            if (result >= 0L) return
            val failure = decodeNativeWebrtcFailure(result)
            if (failure != WebRtcFailure.BUSY) throw WebRtcException(failure)
            bufferedLowCount.awaitChangeFrom(generation)
        }
    }

    /**
     * Suspends while more than [bytes] are buffered on the channel, checking
     * again at each [WebRtcEvent.BufferedLow] and every 250 ms at most.
     *
     * @throws WebRtcException as [bufferedAmount] does, such as once the peer
     * is closed.
     */
    suspend fun awaitBufferedAtMost(bytes: Long) {
        while (true) {
            val generation = bufferedLowCount.value
            if (bufferedAmount() <= bytes) return
            bufferedLowCount.awaitChangeFrom(generation)
        }
    }

    /** Returns the bytes buffered on the channel and not yet acknowledged. */
    suspend fun bufferedAmount(): Long = request { NativeWebrtc.channelBufferedAmount(handle) }

    /**
     * The peer's events. The flow completes when the peer is closed, and
     * cancelling its collector closes the peer.
     */
    fun events(): Flow<WebRtcEvent> = flow {
        val buffer = ByteArray(NATIVE_WEBRTC_MAX_EVENT_BYTES)
        try {
            var open = true
            while (open) {
                val result = pollNativeEvent(handle, buffer)
                if (result < 0L) {
                    val failure = decodeNativeWebrtcFailure(result)
                    if (failure != WebRtcFailure.CLOSED) throw WebRtcException(failure)
                    open = false
                } else if (result > 0L) {
                    decodeNativeWebrtcEvent(buffer, result.toInt())?.let { event ->
                        if (event == WebRtcEvent.BufferedLow) bufferedLowCount.update { it + 1 }
                        emit(event)
                    }
                }
            }
        } catch (e: CancellationException) {
            close()
            throw e
        } finally {
            // Wakes a busy sender, which then sees the close.
            bufferedLowCount.update { it + 1 }
        }
    }

    /** Closes the peer at once and wakes every blocked call. Idempotent. */
    fun close() {
        checkNativeWebrtc(NativeWebrtc.peerClose(handle))
    }
}

/** Waits until the count moves past [generation], or [BUFFERED_RECHECK_MS] at most. */
private suspend fun StateFlow<Long>.awaitChangeFrom(generation: Long) {
    withTimeoutOrNull(BUFFERED_RECHECK_MS) { first { it != generation } }
}

/** Waits for one event; a cancelled caller closes the peer, which ends the wait. */
private suspend fun pollNativeEvent(handle: Long, buffer: ByteArray): Long = runNativeOperation(
    create = { handle },
    cancel = { peer -> NativeWebrtc.peerClose(peer) },
    close = {},
    busy = { WebRtcException(WebRtcFailure.INTERNAL) },
    clear = {},
) { peer -> NativeWebrtc.peerNextEvent(peer, buffer, EVENT_POLL_TIMEOUT_MS) }

private suspend fun description(handle: Long, create: (Long, ByteArray) -> Long): String {
    val out = ByteArray(NATIVE_WEBRTC_MAX_SDP_BYTES)
    val length = request { create(handle, out) }.toInt()
    return out.decodeToString(0, length)
}

/** Runs one blocking request on [Dispatchers.IO] and throws its failure. */
private suspend inline fun request(crossinline call: () -> Long): Long =
    withContext(Dispatchers.IO) { checkNativeWebrtc(call()) }
