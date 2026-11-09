package com.artemchep.keyguard.util.webrtc

import com.artemchep.keyguard.util.ffi.PackedFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The native ABI version both bridges hand-shake against. */
internal const val NATIVE_WEBRTC_ABI_VERSION: Int = 1

/** Largest data channel message. */
internal const val NATIVE_WEBRTC_MAX_MESSAGE_BYTES: Int = 64 * 1024

/** Smallest event buffer: a kind byte and the largest message. */
internal const val NATIVE_WEBRTC_MAX_EVENT_BYTES: Int = 1 + NATIVE_WEBRTC_MAX_MESSAGE_BYTES

/** Smallest SDP buffer. */
internal const val NATIVE_WEBRTC_MAX_SDP_BYTES: Int = 64 * 1024

internal const val NATIVE_WEBRTC_SDP_OFFER: Int = 0
internal const val NATIVE_WEBRTC_SDP_ANSWER: Int = 1

// Bridge codes of `keyguard-webrtc-core`.
internal const val NATIVE_WEBRTC_CODE_INVALID_ARGUMENT: Int = 1
internal const val NATIVE_WEBRTC_CODE_PANIC: Int = 2
internal const val NATIVE_WEBRTC_CODE_INTERNAL: Int = 3
internal const val NATIVE_WEBRTC_CODE_INPUT_TOO_LONG: Int = 4
internal const val NATIVE_WEBRTC_CODE_INVALID_HANDLE: Int = 5
internal const val NATIVE_WEBRTC_CODE_CLOSED: Int = 6
internal const val NATIVE_WEBRTC_CODE_BUSY: Int = 7
internal const val NATIVE_WEBRTC_CODE_TIMEOUT: Int = 8
internal const val NATIVE_WEBRTC_CODE_INVALID_STATE: Int = 9
internal const val NATIVE_WEBRTC_CODE_STACK: Int = 10

// Event kinds.
internal const val NATIVE_WEBRTC_EVENT_ICE_CANDIDATE: Int = 1
internal const val NATIVE_WEBRTC_EVENT_ICE_GATHERING_COMPLETE: Int = 2
internal const val NATIVE_WEBRTC_EVENT_CONNECTION_STATE: Int = 3
internal const val NATIVE_WEBRTC_EVENT_CHANNEL_OPEN: Int = 4
internal const val NATIVE_WEBRTC_EVENT_CHANNEL_MESSAGE: Int = 5
internal const val NATIVE_WEBRTC_EVENT_CHANNEL_CLOSE: Int = 6
internal const val NATIVE_WEBRTC_EVENT_BUFFERED_LOW: Int = 7
internal const val NATIVE_WEBRTC_EVENT_SELECTED_PAIR: Int = 8
internal const val NATIVE_WEBRTC_EVENT_ERROR: Int = 9

/**
 * The packed invalid-argument failure, for arguments a bridge rejects before
 * the native call so that every platform answers alike.
 */
internal const val NATIVE_WEBRTC_INVALID_ARGUMENT: Long = PackedFailure.BRIDGE_INVALID_ARGUMENT

/** [WebRtcFailure] of each bridge code, indexed by the code. */
private val FAILURE_BY_CODE: List<WebRtcFailure?> = listOf(
    null,
    WebRtcFailure.INVALID_ARGUMENT, // NATIVE_WEBRTC_CODE_INVALID_ARGUMENT
    WebRtcFailure.INTERNAL, // NATIVE_WEBRTC_CODE_PANIC
    WebRtcFailure.INTERNAL, // NATIVE_WEBRTC_CODE_INTERNAL
    WebRtcFailure.INPUT_TOO_LONG, // NATIVE_WEBRTC_CODE_INPUT_TOO_LONG
    WebRtcFailure.INVALID_HANDLE, // NATIVE_WEBRTC_CODE_INVALID_HANDLE
    WebRtcFailure.CLOSED, // NATIVE_WEBRTC_CODE_CLOSED
    WebRtcFailure.BUSY, // NATIVE_WEBRTC_CODE_BUSY
    WebRtcFailure.TIMEOUT, // NATIVE_WEBRTC_CODE_TIMEOUT
    WebRtcFailure.INVALID_STATE, // NATIVE_WEBRTC_CODE_INVALID_STATE
    WebRtcFailure.STACK, // NATIVE_WEBRTC_CODE_STACK
)

/** Maps a packed failure scalar to its [WebRtcFailure]. */
internal fun decodeNativeWebrtcFailure(packed: Long): WebRtcFailure {
    val failure = PackedFailure.decode(packed)?.takeIf { it.domain == PackedFailure.DOMAIN_BRIDGE }
    return failure?.let { FAILURE_BY_CODE.getOrNull(it.rawCode) } ?: WebRtcFailure.PROTOCOL
}

/** Returns a non-negative [result], or throws its failure. */
internal fun checkNativeWebrtc(result: Long): Long {
    if (result < 0L) throw WebRtcException(decodeNativeWebrtcFailure(result))
    return result
}

/**
 * Decodes one event of [length] bytes from [buffer], or returns `null` for a
 * kind this version does not know.
 */
internal fun decodeNativeWebrtcEvent(buffer: ByteArray, length: Int): WebRtcEvent? {
    if (length !in 1..buffer.size) protocolFailure()
    return when (buffer[0].toInt()) {
        // Opaque here: the native core parses the candidate the remote peer adds.
        NATIVE_WEBRTC_EVENT_ICE_CANDIDATE -> WebRtcEvent.IceCandidate(buffer.decodeToString(1, length))
        NATIVE_WEBRTC_EVENT_ICE_GATHERING_COMPLETE -> WebRtcEvent.IceGatheringComplete
        NATIVE_WEBRTC_EVENT_CONNECTION_STATE -> {
            if (length != 2) protocolFailure()
            WebRtcEvent.ConnectionState(WebRtcConnectionState.fromWire(buffer[1].toInt()))
        }
        NATIVE_WEBRTC_EVENT_CHANNEL_OPEN -> WebRtcEvent.ChannelOpen
        NATIVE_WEBRTC_EVENT_CHANNEL_MESSAGE -> WebRtcEvent.ChannelMessage(buffer.copyOfRange(1, length))
        NATIVE_WEBRTC_EVENT_CHANNEL_CLOSE -> WebRtcEvent.ChannelClose
        NATIVE_WEBRTC_EVENT_BUFFERED_LOW -> WebRtcEvent.BufferedLow
        NATIVE_WEBRTC_EVENT_SELECTED_PAIR ->
            WebRtcEvent.SelectedPair(decodeSelectedPair(buffer.decodeToString(1, length)))
        NATIVE_WEBRTC_EVENT_ERROR -> {
            if (length != 1 + Int.SIZE_BYTES) protocolFailure()
            WebRtcEvent.Error(readIntLittleEndian(buffer, 1))
        }
        else -> null
    }
}

@Suppress("MagicNumber") // Byte offsets of a little-endian u32.
private fun readIntLittleEndian(buffer: ByteArray, offset: Int): Int =
    (buffer[offset].toInt() and 0xff) or
        ((buffer[offset + 1].toInt() and 0xff) shl 8) or
        ((buffer[offset + 2].toInt() and 0xff) shl 16) or
        ((buffer[offset + 3].toInt() and 0xff) shl 24)

/** The native library answered outside the ABI. */
private fun protocolFailure(cause: Throwable? = null): Nothing =
    throw WebRtcException(WebRtcFailure.PROTOCOL, cause)

private fun parseObject(json: String): JsonObject = try {
    Json.parseToJsonElement(json).jsonObject
} catch (e: IllegalArgumentException) {
    // Also covers SerializationException, a subclass.
    protocolFailure(e)
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

internal fun decodeSelectedPair(json: String): WebRtcSelectedPair {
    val obj = parseObject(json)
    return WebRtcSelectedPair(
        localType = obj.string("localType") ?: protocolFailure(),
        remoteType = obj.string("remoteType") ?: protocolFailure(),
        protocol = obj.string("protocol") ?: protocolFailure(),
    )
}

internal fun encodeConfig(config: WebRtcConfig): String = buildJsonObject {
    put(
        "iceServers",
        buildJsonArray {
            config.iceServers.forEach { server ->
                add(
                    buildJsonObject {
                        put("urls", buildJsonArray { server.urls.forEach { add(JsonPrimitive(it)) } })
                        server.username?.let { put("username", it) }
                        server.credential?.let { put("credential", it) }
                    },
                )
            }
        },
    )
    put("iceTransportPolicy", config.iceTransportPolicy.wire)
    if (config.bindAddresses.isNotEmpty()) {
        put("bindAddresses", buildJsonArray { config.bindAddresses.forEach { add(JsonPrimitive(it)) } })
    }
}.toString()
