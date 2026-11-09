package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.util.isLowerHexDigit
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxLimits
import com.artemchep.keyguard.feature.p2psync.model.JsonLimits
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/** Why the relay refused a request or ended the connection. */
sealed interface RelayError {
    /** A missing or wrong token: HTTP 401, or the `unauthorized` error. */
    data object Unauthorized : RelayError

    /**
     * A frame waited too long for the relay's egress budget and was dropped. On a join, the
     * relay is out of rooms, or the rooms of this address are. Over the per-connection
     * rate, the relay only reads slower.
     */
    data object RateLimited : RelayError

    /** Too many connections from this address: HTTP 429 before the WebSocket upgrade. */
    data object TooManyConnections : RelayError

    /** The room holds as many peers as the relay allows. */
    data object RoomFull : RelayError

    /** The connection is in as many rooms as the relay allows. */
    data object TooManyRooms : RelayError

    /** A message over the relay's size limit. */
    data object FrameTooBig : RelayError

    /** The relay could not read a message of this device. */
    data object BadFrame : RelayError

    /** A message before the join. */
    data object NotJoined : RelayError

    /** The relay does not know a frame of this device; the connection stays open. */
    data object UnknownType : RelayError

    /**
     * The relay speaks another protocol version, or lacks a feature or a limit this build
     * needs, see [RelayServerFrame.Welcome.isCompatible].
     */
    data object Incompatible : RelayError

    /** This device read too slowly and the relay closed the connection. */
    data object SlowConsumer : RelayError

    /** The relay did not confirm the join, or stopped answering pings. */
    data object Timeout : RelayError

    /** There is no connection to the relay right now. */
    data object NotConnected : RelayError

    /** The network failed. */
    data object Network : RelayError

    /** The relay answered the WebSocket upgrade with HTTP [status]. */
    data class Http(val status: Int) : RelayError

    /** An error code this build does not know. */
    data class Unknown(val code: String) : RelayError
}

/** A relay request failed with [error]. */
class RelayException(
    val error: RelayError,
    cause: Throwable? = null,
) : Exception("relay_$error", cause)

/** A message another peer published to this one, or to the whole room. */
class RelayMessage(
    /** The sender's relay peer id. */
    val from: String,
    val data: ByteArray,
)

/** Bytes another peer forwarded to this one in the room [room]. */
class RelayForwardFrame(
    /** The room id; the relay forwards a frame only within the room it names. */
    val room: String,
    /** The sender's relay peer id. */
    val from: String,
    val payload: ByteArray,
)

/**
 * The limits a relay announces in its `welcome`, for each connection. A field the relay
 * leaves out takes the default of `server/relay`.
 */
data class RelayLimits(
    /** The largest text or binary message, either way; at most [RelayCodec.MAX_MESSAGE_BYTES]. */
    val maxMessageBytes: Int = RelayCodec.MAX_MESSAGE_BYTES,
    /** Messages per second the connection may send, with a one-second burst. */
    val messagesPerSecond: Int = 50,
    /** Bytes per second the connection may send, with a one-second burst. */
    val bytesPerSecond: Long = DEFAULT_BYTES_PER_SECOND,
    val roomsPerConnection: Int = 16,
    val peersPerRoom: Int = 4,
    /** Frames from its rooms the relay queues for a connection that does not read, before it closes it. */
    val queuedFrames: Int = 128,
    /** The relay closes a connection that sends nothing for this long. */
    val idleSeconds: Int = 60,
    /**
     * The limits of the items the relay keeps for the devices that are offline, or `null`
     * if it keeps none: its welcome lacks [RelayCodec.FEATURE_MAILBOX].
     */
    val mailbox: RelayMailboxLimits? = null,
) {
    private companion object {
        const val DEFAULT_BYTES_PER_SECOND = 2L * 1024L * 1024L
    }
}

/**
 * The limits of the items a relay keeps, see [RelayMailbox]. A field the relay leaves out
 * takes the protocol limit; one larger than the protocol limit is cut down to it.
 */
data class RelayMailboxLimits(
    /** The largest sealed state item. */
    val stateBytes: Int = MailboxLimits.MAX_STATE_BYTES,
    /** The largest sealed blob item. */
    val blobBytes: Int = MailboxLimits.MAX_BLOB_BYTES,
    /** The longest the relay keeps an item. */
    val ttlSeconds: Long = MailboxLimits.TTL_SECONDS,
)

/** A frame the relay sends. */
internal sealed interface RelayServerFrame {
    /**
     * The first frame of a connection: its peer id [peer], drawn by the relay and the same
     * in every room the connection joins, the protocol [version], the [features] beyond it
     * and the [limits] of the relay.
     */
    data class Welcome(
        val version: Int,
        val peer: String,
        val features: Set<String>,
        val limits: RelayLimits,
    ) : RelayServerFrame {
        /**
         * Whether this build can use the relay: it speaks [RelayCodec.VERSION], forwards
         * frames with [RelayCodec.FEATURE_ROOM_FORWARD], and carries a whole session frame
         * in one forwarded message.
         */
        val isCompatible: Boolean
            get() = version == RelayCodec.VERSION &&
                RelayCodec.FEATURE_ROOM_FORWARD in features &&
                limits.maxMessageBytes >= RelayForwardTransport.MIN_RELAY_MESSAGE_BYTES
    }

    /**
     * The join of [room] succeeded; [peers] are the others in it. [ticket], when the relay
     * sends one, raises the TURN allowance of its ICE endpoint while this connection is in
     * a room.
     */
    data class Joined(
        val room: String,
        val peers: Set<String>,
        val ticket: String? = null,
    ) : RelayServerFrame

    /** The peer [peer] joined [room], or left it when [joined] is `false`. */
    data class Presence(val room: String, val peer: String, val joined: Boolean) : RelayServerFrame

    /** A message published in [room]. */
    class Msg(val room: String, val message: RelayMessage) : RelayServerFrame

    data object Pong : RelayServerFrame

    /** [room] names the room of a refused join. */
    data class Err(val error: RelayError, val room: String? = null) : RelayServerFrame
}

/**
 * The relay wire protocol of `server/relay`: a `welcome` first, then JSON text frames for
 * the rooms, each naming its room, and binary `0x03 | room id | peer id | payload` frames
 * for the bytes forwarded within a room. Every WebSocket message, either way, is at most
 * the `maxMessageBytes` of the welcome.
 */
internal object RelayCodec {
    /** The protocol version this build speaks: `v` of the `welcome`. */
    const val VERSION: Int = 1

    /** The feature of the `welcome` that names the binary forwarded frame below. */
    const val FEATURE_ROOM_FORWARD: String = "room-forward"

    /** The feature of the `welcome` of a relay that keeps items for the offline devices, see [RelayMailbox]. */
    const val FEATURE_MAILBOX: String = "mailbox"

    /** The largest WebSocket message this client sends or reads; a relay may allow less. */
    const val MAX_MESSAGE_BYTES: Int = 64 * 1024

    /** Bytes in a peer id; it is written as lowercase hex. */
    const val PEER_ID_BYTES: Int = 16

    /** Bytes in a room id; it is written as lowercase hex. */
    const val ROOM_ID_BYTES: Int = 32

    /** Bytes in a ticket; it is written as lowercase hex. */
    const val TICKET_BYTES: Int = 16

    /** The tag byte of a forwarded binary frame; relays refuse the old `0x02` without a room. */
    const val FORWARD_TAG: Byte = 0x03

    /** Where the peer id of a forwarded binary frame starts, after the tag and the room id. */
    const val FORWARD_PEER_OFFSET: Int = 1 + ROOM_ID_BYTES

    const val FORWARD_HEADER_SIZE: Int = FORWARD_PEER_OFFSET + PEER_ID_BYTES

    /** The largest payload of one forwarded frame. */
    const val MAX_FORWARD_PAYLOAD: Int = MAX_MESSAGE_BYTES - FORWARD_HEADER_SIZE

    /** Addresses every other peer of the room. */
    const val BROADCAST: String = "*"

    /** The longest text frame this client reads; the relay never sends more. */
    private const val MAX_INBOUND_TEXT = 2 * MAX_MESSAGE_BYTES

    private const val MAX_ERROR_CODE_LENGTH = 64

    val PING: String = encode(RelayClientFrame.Ping)

    fun join(room: String): String = encode(RelayClientFrame.Join(room = room))

    fun pub(room: String, to: String, data: ByteArray): String =
        encode(RelayClientFrame.Pub(room = room, to = to, data = Base64.Default.encode(data)))

    fun leave(room: String): String = encode(RelayClientFrame.Leave(room = room))

    /** Returns the frame that forwards [payload] to the peer [to] in the room [room]. */
    fun forward(room: String, to: String, payload: ByteArray): ByteArray {
        val out = ByteArray(FORWARD_HEADER_SIZE + payload.size)
        out[0] = FORWARD_TAG
        room.hexToByteArray().copyInto(out, destinationOffset = 1)
        to.hexToByteArray().copyInto(out, destinationOffset = FORWARD_PEER_OFFSET)
        payload.copyInto(out, destinationOffset = FORWARD_HEADER_SIZE)
        return out
    }

    /** Returns the frame in [text], or `null` if it breaks the protocol or this build does not know it. */
    fun decodeText(text: String): RelayServerFrame? = text
        .takeIf { it.length <= MAX_INBOUND_TEXT }
        ?.let(::decodeServerFrameOrNull)
        ?.toFrame()

    /**
     * Returns the forwarded frame in [bytes], or `null` if it breaks the protocol. The frame
     * holds copies: the caller may reuse [bytes].
     */
    fun decodeBinary(bytes: ByteArray): RelayForwardFrame? {
        if (bytes.size < FORWARD_HEADER_SIZE || bytes.size > MAX_MESSAGE_BYTES || bytes[0] != FORWARD_TAG) {
            return null
        }
        return RelayForwardFrame(
            room = bytes.copyOfRange(1, FORWARD_PEER_OFFSET).toHexString(),
            from = bytes.copyOfRange(FORWARD_PEER_OFFSET, FORWARD_HEADER_SIZE).toHexString(),
            payload = bytes.copyOfRange(FORWARD_HEADER_SIZE, bytes.size),
        )
    }

    fun errorOf(code: String): RelayError = when (code) {
        "unauthorized" -> RelayError.Unauthorized
        "rate_limited" -> RelayError.RateLimited
        "room_full" -> RelayError.RoomFull
        "frame_too_big" -> RelayError.FrameTooBig
        "bad_frame" -> RelayError.BadFrame
        "not_joined" -> RelayError.NotJoined
        "too_many_rooms" -> RelayError.TooManyRooms
        "unknown_type" -> RelayError.UnknownType
        else -> RelayError.Unknown(code.take(MAX_ERROR_CODE_LENGTH))
    }

    fun isPeerId(value: String): Boolean = isLowerHex(value, PEER_ID_BYTES)

    fun isRoomId(value: String): Boolean = isLowerHex(value, ROOM_ID_BYTES)

    fun isTicket(value: String): Boolean = isLowerHex(value, TICKET_BYTES)

    private fun encode(frame: RelayClientFrame): String =
        relayJson.encodeToString(RelayClientFrame.serializer(), frame)
}

/** Unknown fields are ignored, as the relay ignores those of the client. */
private val relayJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Relay frames are flat: an object of strings, a list of peers, or the limits of a welcome.
 * The tree parser recurses on nested arrays without a bound, so a frame is scanned first.
 */
private val relayJsonLimits = JsonLimits(maxDepth = 4, maxNodes = 1024)

// A SerializationException is an IllegalArgumentException.
private fun decodeServerFrameOrNull(text: String): RelayServerFrameJson? = try {
    if (!relayJsonLimits.admits(text.encodeToByteArray())) return null
    val element = relayJson.parseToJsonElement(text)
    // The type is a string, never a bare word.
    val type = (element as? JsonObject)?.get("t") as? JsonPrimitive
    element
        .takeIf { type?.isString == true }
        ?.let { relayJson.decodeFromJsonElement(RelayServerFrameJson.serializer(), it) }
} catch (expected: IllegalArgumentException) {
    null
}

/** A frame the client sends, as `ClientFrame` of `server/relay` reads it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
private sealed interface RelayClientFrame {
    @Serializable
    @SerialName("join")
    class Join(val room: String) : RelayClientFrame

    @Serializable
    @SerialName("pub")
    class Pub(val room: String, val to: String, val data: String) : RelayClientFrame

    @Serializable
    @SerialName("leave")
    class Leave(val room: String) : RelayClientFrame

    @Serializable
    @SerialName("ping")
    data object Ping : RelayClientFrame
}

/** A frame the relay sends, as `ServerFrame` of `server/relay` writes it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
private sealed interface RelayServerFrameJson {
    /** Returns the frame, or `null` if it breaks the protocol. */
    fun toFrame(): RelayServerFrame?

    /** Unknown features and limits are ignored. */
    @Serializable
    @SerialName("welcome")
    class Welcome(
        val v: Int,
        val peer: String,
        val features: List<String> = emptyList(),
        val limits: Limits = Limits(),
    ) : RelayServerFrameJson {
        override fun toFrame(): RelayServerFrame? = limits
            .toLimits(mailbox = RelayCodec.FEATURE_MAILBOX in features)
            ?.takeIf { RelayCodec.isPeerId(peer) }
            ?.let { RelayServerFrame.Welcome(v, peer, features.toSet(), it) }
    }

    @Serializable
    class Limits(
        val maxMessageBytes: Long? = null,
        val messagesPerSecond: Long? = null,
        val bytesPerSecond: Long? = null,
        val roomsPerConnection: Long? = null,
        val peersPerRoom: Long? = null,
        val queuedFrames: Long? = null,
        val idleSeconds: Long? = null,
        val mailboxStateBytes: Long? = null,
        val mailboxBlobBytes: Long? = null,
        val mailboxTtlSeconds: Long? = null,
    ) {
        /**
         * Returns the limits, or `null` if one of the connection is not positive. The limits
         * of the items count only for a relay that keeps them, [mailbox]; one that is not
         * positive leaves it without: no item fits.
         */
        fun toLimits(mailbox: Boolean): RelayLimits? {
            val given = listOfNotNull(
                maxMessageBytes,
                messagesPerSecond,
                bytesPerSecond,
                roomsPerConnection,
                peersPerRoom,
                queuedFrames,
                idleSeconds,
            )
            if (given.any { it <= 0L }) return null
            val defaults = RelayLimits()
            return RelayLimits(
                maxMessageBytes = maxMessageBytes.toIntOr(defaults.maxMessageBytes, max = RelayCodec.MAX_MESSAGE_BYTES),
                messagesPerSecond = messagesPerSecond.toIntOr(defaults.messagesPerSecond),
                bytesPerSecond = bytesPerSecond ?: defaults.bytesPerSecond,
                roomsPerConnection = roomsPerConnection.toIntOr(defaults.roomsPerConnection),
                peersPerRoom = peersPerRoom.toIntOr(defaults.peersPerRoom),
                queuedFrames = queuedFrames.toIntOr(defaults.queuedFrames),
                idleSeconds = idleSeconds.toIntOr(defaults.idleSeconds),
                mailbox = if (mailbox) toMailboxLimits() else null,
            )
        }

        private fun toMailboxLimits(): RelayMailboxLimits? {
            val given = listOfNotNull(mailboxStateBytes, mailboxBlobBytes, mailboxTtlSeconds)
            if (given.any { it <= 0L }) return null
            val defaults = RelayMailboxLimits()
            return RelayMailboxLimits(
                stateBytes = mailboxStateBytes.toIntOr(defaults.stateBytes, max = defaults.stateBytes),
                blobBytes = mailboxBlobBytes.toIntOr(defaults.blobBytes, max = defaults.blobBytes),
                ttlSeconds = mailboxTtlSeconds?.coerceAtMost(defaults.ttlSeconds) ?: defaults.ttlSeconds,
            )
        }

        private fun Long?.toIntOr(default: Int, max: Int = Int.MAX_VALUE): Int =
            this?.coerceAtMost(max.toLong())?.toInt() ?: default
    }

    @Serializable
    @SerialName("joined")
    class Joined(
        val room: String,
        val peers: List<String>,
        val ticket: String? = null,
    ) : RelayServerFrameJson {
        // A malformed ticket only costs the larger TURN allowance, not the join.
        override fun toFrame(): RelayServerFrame? = peers
            .takeIf { RelayCodec.isRoomId(room) && it.all(RelayCodec::isPeerId) }
            ?.let { RelayServerFrame.Joined(room, it.toSet(), ticket?.takeIf(RelayCodec::isTicket)) }
    }

    @Serializable
    @SerialName("presence")
    class Presence(val room: String, val peer: String, val state: String) : RelayServerFrameJson {
        override fun toFrame(): RelayServerFrame? {
            if (!RelayCodec.isRoomId(room) || !RelayCodec.isPeerId(peer)) return null
            return when (state) {
                "join" -> RelayServerFrame.Presence(room, peer, joined = true)
                "leave" -> RelayServerFrame.Presence(room, peer, joined = false)
                else -> null
            }
        }
    }

    @Serializable
    @SerialName("msg")
    class Msg(val room: String, val from: String, val data: String) : RelayServerFrameJson {
        override fun toFrame(): RelayServerFrame? {
            if (!RelayCodec.isRoomId(room) || !RelayCodec.isPeerId(from)) return null
            return decodeBase64(data)?.let { RelayServerFrame.Msg(room, RelayMessage(from, it)) }
        }
    }

    @Serializable
    @SerialName("pong")
    data object Pong : RelayServerFrameJson {
        override fun toFrame(): RelayServerFrame = RelayServerFrame.Pong
    }

    @Serializable
    @SerialName("err")
    class Err(val code: String, val room: String? = null) : RelayServerFrameJson {
        override fun toFrame(): RelayServerFrame? = RelayServerFrame
            .Err(RelayCodec.errorOf(code), room)
            .takeIf { room == null || RelayCodec.isRoomId(room) }
    }
}

private fun decodeBase64(text: String): ByteArray? = try {
    Base64.Default.decode(text)
} catch (expected: IllegalArgumentException) {
    null
}

private fun isLowerHex(value: String, bytes: Int): Boolean =
    value.length == bytes * 2 && value.all(Char::isLowerHexDigit)
