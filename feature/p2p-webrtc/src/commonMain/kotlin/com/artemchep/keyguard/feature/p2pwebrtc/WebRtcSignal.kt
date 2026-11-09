package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One WebRTC signaling message, the opaque payload of a `Signaling` message. [sid] names
 * one connection attempt, so a late answer or candidate of an older attempt is ignored.
 */
@Serializable
internal data class WebRtcSignal(
    val kind: Kind,
    val sid: String,
    val sdp: String? = null,
    /** A candidate as the native stack writes it, opaque here; the peer's stack parses it. */
    val candidate: String? = null,
    val t: String = TYPE,
    val v: Int = VERSION,
) {
    @Serializable
    enum class Kind {
        @SerialName("offer")
        OFFER,

        @SerialName("answer")
        ANSWER,

        @SerialName("candidate")
        CANDIDATE,
    }

    companion object {
        /** Tells WebRTC signals apart from other payloads on the same signaling channel. */
        const val TYPE: String = "webrtc"

        const val VERSION: Int = 1
    }
}

internal object WebRtcSignalCodec {
    /** The longest SDP the native stack produces. */
    private const val MAX_SDP_LENGTH = 64 * 1024

    /** The longest candidate the native stack takes; a hostile peer's longer ones are dropped. */
    private const val MAX_CANDIDATE_LENGTH = 4 * 1024

    private const val SID_LENGTH = 16

    private val json = P2pJson

    fun encode(signal: WebRtcSignal): ByteArray = json.encodeToString(signal).encodeToByteArray()

    /** Returns the signal in [bytes], or `null` for anything else. */
    fun decode(bytes: ByteArray): WebRtcSignal? =
        decodeOrNull<WebRtcSignal>(bytes.decodeToString())?.takeIf(::isValid)

    private fun isValid(signal: WebRtcSignal): Boolean {
        val header = signal.t == WebRtcSignal.TYPE &&
            signal.v == WebRtcSignal.VERSION &&
            isSid(signal.sid)
        val body = when (signal.kind) {
            WebRtcSignal.Kind.OFFER,
            WebRtcSignal.Kind.ANSWER,
            -> signal.sdp != null && signal.sdp.length <= MAX_SDP_LENGTH

            WebRtcSignal.Kind.CANDIDATE ->
                signal.candidate != null && signal.candidate.length <= MAX_CANDIDATE_LENGTH
        }
        return header && body
    }

    fun isSid(value: String): Boolean =
        value.length == SID_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    // A SerializationException is an IllegalArgumentException.
    private inline fun <reified T> decodeOrNull(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (expected: IllegalArgumentException) {
        null
    }
}
