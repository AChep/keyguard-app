package com.artemchep.keyguard.util.webrtc

/** The stable category of a [WebRtcException]. */
enum class WebRtcFailure {
    /** An argument breaks the contract, such as a malformed SDP or configuration. */
    INVALID_ARGUMENT,

    /** An input is over its byte limit, such as a message over 64 KiB. */
    INPUT_TOO_LONG,

    /** The peer or runtime was never created by this process. */
    INVALID_HANDLE,

    /** The peer or its runtime is closed, also while the call was blocked on it. */
    CLOSED,

    /**
     * Too many runtimes or peers are live. Also the channel buffering more than
     * 1 MiB, which [WebRtcPeer.send] waits out instead of throwing.
     */
    BUSY,

    /** The WebRTC stack did not answer within 10 seconds. */
    TIMEOUT,

    /** The call is not valid now, such as a send before the channel opens. */
    INVALID_STATE,

    /** The WebRTC stack rejected the request. */
    STACK,

    /** The native library is missing or has another ABI version. */
    UNAVAILABLE,

    /** The native library answered outside the ABI. */
    PROTOCOL,

    /** A bug in the native bridge. */
    INTERNAL,
}

/** A failed WebRTC call. The message is fixed per [failure] and never carries SDP or data. */
class WebRtcException(
    val failure: WebRtcFailure,
    cause: Throwable? = null,
) : Exception("webrtc_" + failure.name.lowercase(), cause)
