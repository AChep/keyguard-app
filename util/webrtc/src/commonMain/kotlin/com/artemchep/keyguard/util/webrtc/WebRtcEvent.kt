package com.artemchep.keyguard.util.webrtc

/** An event of a [WebRtcPeer], in delivery order. */
sealed interface WebRtcEvent {
    /**
     * A local candidate to send to the remote peer over signaling, as the
     * `RTCIceCandidateInit` JSON the remote [WebRtcPeer.addIceCandidate] takes.
     * Opaque: the native core is the one parser of candidates.
     */
    data class IceCandidate(val json: String) : WebRtcEvent

    /** Local ICE gathering finished. */
    data object IceGatheringComplete : WebRtcEvent

    /** The connection state changed. */
    data class ConnectionState(val state: WebRtcConnectionState) : WebRtcEvent

    /** The data channel opened; [WebRtcPeer.send] works from now on. */
    data object ChannelOpen : WebRtcEvent

    /** The data channel received one message. */
    class ChannelMessage(val data: ByteArray) : WebRtcEvent {
        override fun toString(): String = "ChannelMessage(size=${data.size})"
    }

    /** The data channel closed. */
    data object ChannelClose : WebRtcEvent

    /** The channel's buffered amount fell to 256 KiB. */
    data object BufferedLow : WebRtcEvent

    /** ICE selected a candidate pair. */
    data class SelectedPair(val pair: WebRtcSelectedPair) : WebRtcEvent

    /** A failure outside any call; [code] is one of the `CODE_*` constants. */
    data class Error(val code: Int) : WebRtcEvent {
        companion object {
            /** The stack rejected a remote candidate. */
            const val CODE_REMOTE_CANDIDATE: Int = 1

            /** A STUN or TURN server failed while gathering. */
            const val CODE_ICE_SERVER: Int = 2

            /** The data channel reported an error. */
            const val CODE_CHANNEL: Int = 3
        }
    }
}

/** The peer connection state. */
enum class WebRtcConnectionState {
    UNKNOWN,
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    ;

    internal companion object {
        // The wire value is the ordinal.
        fun fromWire(value: Int): WebRtcConnectionState = entries.getOrElse(value) { UNKNOWN }
    }
}

/**
 * The selected candidate pair. The types are `host`, `srflx`, `prflx` or
 * `relay`; the protocol is `udp` or `tcp`.
 */
data class WebRtcSelectedPair(
    val localType: String,
    val remoteType: String,
    val protocol: String,
)

/** The type of a remote session description. */
enum class WebRtcSdpType {
    OFFER,
    ANSWER,
}
