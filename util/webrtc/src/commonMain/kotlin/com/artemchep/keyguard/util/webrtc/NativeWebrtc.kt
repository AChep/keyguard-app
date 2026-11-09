package com.artemchep.keyguard.util.webrtc

/**
 * The complete per-platform surface of the native WebRTC core (ABI v1).
 *
 * Every function returns the native scalar unchanged: a handle, a length or
 * zero on success, a packed failure (negative) otherwise. Implementations load
 * the native library, verify the ABI version once and throw
 * [WebRtcException] with [WebRtcFailure.UNAVAILABLE] when they cannot. An
 * argument the bridge cannot even marshal, such as a string with an unpaired
 * surrogate, answers [NATIVE_WEBRTC_INVALID_ARGUMENT] on every platform.
 *
 * Calls block the calling thread: the requests for at most 10 seconds,
 * [peerNextEvent] up to its timeout or until [peerClose].
 */
@Suppress("TooManyFunctions") // One function per ABI export.
internal expect object NativeWebrtc {
    /** Loads the library and returns its ABI version without checking it. */
    fun abiVersion(): Int

    fun runtimeNew(): Long

    fun runtimeShutdown(runtime: Long): Long

    fun peerNew(runtime: Long, configJson: String): Long

    /** Writes the SDP into [out] of at least [NATIVE_WEBRTC_MAX_SDP_BYTES]. */
    fun peerCreateOffer(peer: Long, out: ByteArray): Long

    /** Writes the SDP into [out] of at least [NATIVE_WEBRTC_MAX_SDP_BYTES]. */
    fun peerCreateAnswer(peer: Long, out: ByteArray): Long

    fun peerSetRemote(peer: Long, kind: Int, sdp: String): Long

    fun peerAddIceCandidate(peer: Long, candidateJson: String): Long

    fun peerCreateChannel(peer: Long, label: String): Long

    fun channelSend(peer: Long, data: ByteArray, offset: Int, length: Int): Long

    fun channelBufferedAmount(peer: Long): Long

    /** Writes the event into [out] of at least [NATIVE_WEBRTC_MAX_EVENT_BYTES]. */
    fun peerNextEvent(peer: Long, out: ByteArray, timeoutMs: Int): Long

    fun peerClose(peer: Long): Long
}
