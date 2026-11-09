package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.transport.Signaling
import com.artemchep.keyguard.feature.p2psync.transport.SignalingMessage
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [Signaling] through a relay room, for the devices of a sync group or of one enrollment.
 *
 * Unlike the relay, it names peers by their **device ids**: [presence] holds the devices
 * that announced themselves, [incoming] carries the sender's device id, and [send] takes
 * the recipient's. A device announces itself, and the transports it supports, with a
 * `hello` to every peer that joins.
 *
 * Every message is an EncString (AES-CBC-256 + HMAC-SHA256) under the room's signaling
 * key, of a JSON envelope `{v, from, to, kind, relay, relayTo, nonce, body, transports}`;
 * so the sender, the recipient and their relay ids are authenticated, and the relay reads
 * none of them. Relay ids are fresh for each connection, so they stand in for a clock: a
 * message counts only if its sender's relay id is the one the relay stamped on it and is
 * in the room, and if it names the current relay id of this device. A message that does
 * not decrypt, is not for this device, or repeats a nonce of this connection is ignored.
 */
class RelaySignaling(
    private val client: RelayClient,
    private val room: RelayRoom,
    private val localDeviceId: String,
    private val cipherEncryptor: CipherEncryptor,
    /** Cryptographically secure random bytes, such as `CryptoGenerator.seed`. */
    private val randomBytes: (Int) -> ByteArray,
    /** The transports this device supports in the room; asked for each `hello`. */
    private val transports: () -> Set<TransportKind>,
) : Signaling {
    companion object {
        const val VERSION: Int = 1

        private const val NONCE_BYTES = 16

        /** The nonces of one connection that are remembered; the oldest go first. */
        private const val NONCE_CAPACITY = 4096

        private const val INCOMING_BUFFER = 64
    }

    /** Device id → relay peer id, for the devices seen in the current connection; one device per relay peer. */
    private val devices = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Device id → the transports its last `hello` listed. */
    private val deviceTransports = MutableStateFlow<Map<String, Set<TransportKind>>>(emptyMap())

    private val presenceSink = MutableStateFlow<Set<String>>(emptySet())

    private val incomingSink = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = INCOMING_BUFFER)

    /** The nonces of the current connection; touched by [run] only. */
    private val nonces = LinkedHashSet<String>()

    /** Relay peers greeted in the current connection; touched by [run] only. */
    private val greeted = mutableSetOf<String>()

    /** This device's relay peer id as [run] last saw it; written by [run] only. */
    private var self: String? = null

    /** The device ids of the group's devices in the room. */
    override val presence: StateFlow<Set<String>> = presenceSink.asStateFlow()

    /** The relay peer id of each device in the room: each connection of a device has a new one. */
    val relayPeers: StateFlow<Map<String, String>> = devices.asStateFlow()

    override val incoming: SharedFlow<SignalingMessage> = incomingSink.asSharedFlow()

    init {
        require(isDeviceId(localDeviceId)) { "Not a device id" }
    }

    /** Returns the relay peer id of the device [deviceId], if it is in the room. */
    fun relayPeerOf(deviceId: String): String? = devices.value[deviceId]

    /** Returns the transports the device [deviceId] supports, if it announced them. */
    fun transportsOf(deviceId: String): Set<TransportKind>? = deviceTransports.value[deviceId]

    /**
     * Sends [payload] to the device [to], at its current relay id.
     *
     * @throws RelayException when the relay is not joined or the message is too big.
     * @throws TransportException when [to] did not announce itself in the room.
     */
    override suspend fun send(to: String, payload: ByteArray) {
        require(isDeviceId(to)) { "Not a device id" }
        val relay = devices.value[to]
            ?: throw TransportException("The device is not in the relay room")
        client.publish(relay, seal(Kind.SIGNAL, to, relay, payload))
    }

    /** Reads the room until cancelled: run it while the [client] runs. */
    suspend fun run() {
        val room = combine(client.state, client.presence) { _, peers -> Event.Room(peers) }
        merge(room, client.messages.map { Event.Message(it) }).collect { event ->
            when (event) {
                is Event.Room -> onRoom(event.peers)
                is Event.Message -> onMessage(event.message)
            }
        }
    }

    /**
     * Returns this device's relay id in the current connection; a new one forgets what
     * belongs to the old connection. The client reports a join before the messages of its
     * connection, so a message is checked against the connection it came on.
     */
    private fun connection(): String? {
        val current = (client.state.value as? RelayState.Joined)?.self
        if (current != self) {
            // A new peer id: every peer has to hear from this device again.
            self = current
            greeted.clear()
            nonces.clear()
            if (current == null) updateDevices { emptyMap() }
        }
        return current
    }

    private suspend fun onRoom(peers: Set<String>) {
        connection() ?: return
        updateDevices { map -> map.filterValues { it in peers } }
        greeted.retainAll(peers)
        peers.filter { it !in greeted }.forEach { greet(it) }
    }

    private suspend fun onMessage(message: RelayMessage) {
        val envelope = open(message) ?: return
        // A relay peer is one device, the one it named last: a member that holds the key of
        // the room could otherwise name any number of devices from one connection.
        updateDevices { map -> map.filterValues { it != message.from } + (envelope.from to message.from) }
        when (envelope.kind) {
            Kind.HELLO -> {
                val kinds = envelope.transports.mapNotNull(::transportKindOf).toSet()
                deviceTransports.update { it + (envelope.from to kinds) }
                // A room may restart on a shared WebSocket without changing its relay
                // id. Answer each announcement, even from a peer greeted before. A
                // directed HELLO is the answer and needs no answer of its own.
                if (envelope.to == RelayCodec.BROADCAST) greet(message.from, toDevice = envelope.from)
            }

            Kind.SIGNAL -> {
                val body = P2pEncoding.base64UrlOrNull(envelope.body) ?: return
                incomingSink.emit(SignalingMessage(envelope.from, body))
            }
        }
    }

    private suspend fun greet(relayPeer: String, toDevice: String = RelayCodec.BROADCAST) {
        greeted += relayPeer
        try {
            client.publish(relayPeer, seal(Kind.HELLO, toDevice, relayPeer, ByteArray(0)))
        } catch (expected: RelayException) {
            // The connection is gone; the next one greets again.
            greeted -= relayPeer
        }
    }

    private fun updateDevices(transform: (Map<String, String>) -> Map<String, String>) {
        devices.update(transform)
        val present = devices.value.keys
        deviceTransports.update { map -> map.filterKeys { it in present } }
        presenceSink.value = present
    }

    /** Seals a message of [kind] for the device [to], at the relay peer [relayTo]. */
    private fun seal(kind: Kind, to: String, relayTo: String, body: ByteArray): ByteArray {
        val relay = (client.state.value as? RelayState.Joined)?.self
            ?: throw RelayException(RelayError.NotConnected)
        val envelope = SignalingEnvelope(
            v = VERSION,
            from = localDeviceId,
            to = to,
            kind = kind,
            relay = relay,
            relayTo = relayTo,
            nonce = P2pEncoding.base64Url(randomBytes(NONCE_BYTES)),
            body = P2pEncoding.base64Url(body),
            transports = if (kind == Kind.HELLO) transports().map(::transportName).sorted() else emptyList(),
        )
        val plainText = P2pJson.encodeToString(SignalingEnvelope.serializer(), envelope).encodeToByteArray()
        return cipherEncryptor.encode2(
            cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            plainText = plainText,
            symmetricCryptoKey = room.signalingKey,
        ).encodeToByteArray()
    }

    /**
     * Returns the envelope of [message] if it is authentic, for this device in its current
     * connection, from a peer in the room, and new in this connection.
     */
    private fun open(message: RelayMessage): SignalingEnvelope? {
        val self = connection() ?: return null
        return decrypt(cipherEncryptor, room, message.data)
            ?.let(::decodeEnvelope)
            ?.takeIf { envelope ->
                val addressed = envelope.to == localDeviceId ||
                    envelope.to == RelayCodec.BROADCAST && envelope.kind == Kind.HELLO
                envelope.v == VERSION &&
                    addressed &&
                    // The nonces of a connection are kept: each takes 16 bytes, no more.
                    P2pEncoding.canonicalBase64UrlOrNull(envelope.nonce)?.size == NONCE_BYTES &&
                    isDeviceId(envelope.from) &&
                    envelope.from != localDeviceId &&
                    envelope.relay == message.from &&
                    message.from in client.presence.value &&
                    envelope.relayTo == self
            }
            // A nonce counts once per connection; the oldest are forgotten first.
            ?.takeIf { nonces.add(it.nonce) }
            ?.also { if (nonces.size > NONCE_CAPACITY) nonces.remove(nonces.first()) }
    }

    private sealed interface Event {
        class Room(val peers: Set<String>) : Event

        class Message(val message: RelayMessage) : Event
    }
}

private const val MAX_DEVICE_ID_LENGTH = 128

private const val TRANSPORT_WEBRTC = "webrtc"

private const val TRANSPORT_RELAY = "relay"

private fun isDeviceId(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_DEVICE_ID_LENGTH && value != RelayCodec.BROADCAST

/** The name of [kind] in a `hello`. */
internal fun transportName(kind: TransportKind): String = when (kind) {
    TransportKind.WEBRTC -> TRANSPORT_WEBRTC
    TransportKind.RELAY -> TRANSPORT_RELAY
}

/** Returns the transport a `hello` names [name], or `null` if this build does not know it. */
internal fun transportKindOf(name: String): TransportKind? = when (name) {
    TRANSPORT_WEBRTC -> TransportKind.WEBRTC
    TRANSPORT_RELAY -> TransportKind.RELAY
    else -> null
}

/** Returns the plain text of a message sealed under the signaling key of [room], if it is authentic. */
private fun decrypt(cipherEncryptor: CipherEncryptor, room: RelayRoom, data: ByteArray): ByteArray? {
    val cipherText = data.decodeToString()
    // Only the authenticated type; the others either lack a MAC or use RSA.
    if (!cipherText.startsWith(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.type + ".")) return null
    return try {
        cipherEncryptor.decode2(cipherText, symmetricCryptoKey = room.signalingKey).data
    } catch (expected: DecodeException) {
        null
    }
}

// A SerializationException is an IllegalArgumentException.
private fun decodeEnvelope(plainText: ByteArray): SignalingEnvelope? = try {
    P2pJson.decodeFromString(SignalingEnvelope.serializer(), plainText.decodeToString())
} catch (expected: IllegalArgumentException) {
    null
}

@Serializable
internal enum class Kind {
    /** Announces the sender's device id and transports; [SignalingEnvelope.to] is `*`. */
    @SerialName("hello")
    HELLO,

    /** Carries an opaque payload of a transport. */
    @SerialName("signal")
    SIGNAL,
}

/** The authenticated content of one signaling message. */
@Serializable
internal data class SignalingEnvelope(
    val v: Int,
    /** The sender's device id. */
    val from: String,
    /** The recipient's device id, or `*` for a hello. */
    val to: String,
    val kind: Kind,
    /** The sender's relay peer id, which the relay stamps on the message too. */
    val relay: String,
    /** The recipient's relay peer id, in the connection the sender sent to. */
    val relayTo: String,
    /** 16 random bytes, base64url. */
    val nonce: String,
    /** The payload, base64url. */
    val body: String = "",
    /** The transports the sender supports, such as `webrtc` and `relay`; a hello's only. */
    val transports: List<String> = emptyList(),
)
