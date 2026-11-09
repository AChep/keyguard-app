package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.writeBigEndian
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/** A [ByteStream] could not be opened or was used after it closed. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The receive credit of the relay-forwarded streams of one relay connection, shared by
 * every room on it: how many `DATA` frames their peers may send to this device before it
 * reads them. While this device does not read, the relay queues those frames for the
 * connection, and closes it once [RelayLimits.queuedFrames] wait; [total] stays under that,
 * with a quarter left for the other frames of the connection.
 *
 * Each stream aims to hold an equal [share], and takes credit only from what is free: all
 * the streams of the connection together never hold more than [total]. A stream that
 * holds more than its share since others opened gives back what it does not need as it
 * reads.
 */
class RelayForwardCredit(
    /** The `DATA` frames the streams may hold together. */
    val total: Int,
) {
    companion object {
        /** A quarter of the queue is left for the other frames. */
        private const val RESERVE_DIVISOR = 4

        /** The credit of a connection to a relay with [limits]. */
        fun of(limits: RelayLimits): RelayForwardCredit =
            RelayForwardCredit((limits.queuedFrames - limits.queuedFrames / RESERVE_DIVISOR).coerceAtLeast(1))
    }

    private val lock = SynchronizedObject()

    private var streams = 0

    private var free = total

    fun register() {
        synchronized(lock) { streams += 1 }
    }

    /** A stream closed; [held] is the credit it held. */
    fun unregister(held: Int) {
        synchronized(lock) {
            streams -= 1
            free += held
        }
    }

    /** The credit each stream aims to hold: an equal part of [total], at most [RelayForwardTransport.MAX_WINDOW]. */
    fun share(): Int = synchronized(lock) {
        (total / streams.coerceAtLeast(1)).coerceIn(1, RelayForwardTransport.MAX_WINDOW)
    }

    /** Takes up to [wanted] of the free credit; returns how much it took. */
    fun take(wanted: Int): Int = synchronized(lock) {
        min(wanted, free).coerceAtLeast(0).also { free -= it }
    }

    /** Gives back [count] credit, of frames a stream read. */
    fun release(count: Int) {
        synchronized(lock) { free += count }
    }
}

/**
 * Streams between two devices over the relay's binary frames in the room of [client], for
 * when WebRTC can not connect them. The relay forwards the bytes as they are, only within
 * that room; the Noise session on top encrypts them.
 *
 * Inside a forwarded frame: `u8 type | u64 stream id | data`. The initiator repeats `OPEN`
 * until the responder answers `ACCEPT`, then both send `DATA` and end with `CLOSE`. Each
 * WebSocket message keeps its boundary, so each `DATA` frame is one stream message.
 *
 * Each end may send as many `DATA` frames as the other end gave it credit for, then waits.
 * `OPEN` and `ACCEPT` carry the first credit (`data` = `u32` count), and a reader gives
 * more with `CREDIT` (`data` = `u32` count) as it takes them. A reader takes its credit
 * from the [RelayForwardCredit] of its relay connection, so the relay never queues more
 * for a slow reader than it allows, and doesn't close its connection as a slow consumer. A
 * peer that sends past its credit breaks the stream.
 *
 * Streams live as long as the relay connection: a reconnect gives every peer a new relay
 * id, so [run] closes them. A [RelayError.RateLimited] means the relay dropped a frame,
 * which a reliable stream can not hide, so it closes them too.
 */
class RelayForwardTransport(
    private val client: RelayClient,
    /** Returns the relay peer id of a device, such as `RelaySignaling.relayPeerOf`. */
    private val resolveRelayPeer: (deviceId: String) -> String?,
    /** Random bytes for stream ids, such as `CryptoGenerator.seed`. */
    private val randomBytes: (Int) -> ByteArray,
    /** How long [connect] waits for the other device. */
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : Transport {
    companion object {
        /** 30 seconds. */
        const val CONNECT_TIMEOUT_MS: Long = 30_000L

        /** How often an initiator repeats `OPEN`. */
        const val OPEN_RESEND_MS: Long = 2_000L

        /** A responder takes only an `OPEN` this fresh, so not one of a dead attempt. */
        private const val OPEN_FRESH_MS: Long = 2 * OPEN_RESEND_MS

        /** The largest message of a stream. */
        const val MAX_MESSAGE_SIZE: Int = RelayCodec.MAX_FORWARD_PAYLOAD - HEADER_SIZE

        /** The smallest `maxMessageBytes` of a relay that forwards a whole session frame. */
        const val MIN_RELAY_MESSAGE_BYTES: Int =
            FrameCodec.MAX_FRAME_SIZE + NoiseByteStream.TAG_SIZE + HEADER_SIZE + RelayCodec.FORWARD_HEADER_SIZE

        /** The most `DATA` frames a stream may have unread at the other end. */
        const val MAX_WINDOW: Int = 16

        /** [RelayForwardStream.credit] of a closed stream. */
        private const val CLOSED = -1

        /** How often a reader whose peer holds no credit asks the connection for some again. */
        private const val STARVED_RETRY_MS: Long = 100L

        /** How long a `CLOSE` of an attempt that was given up may wait to leave. */
        private const val CLOSE_TIMEOUT_MS: Long = 1_000L
    }

    override val kind: TransportKind = TransportKind.RELAY

    private val streams = MutableStateFlow<Map<StreamKey, RelayForwardStream>>(emptyMap())

    /** The latest `OPEN` of each relay peer that is not accepted yet. */
    private val opens = MutableStateFlow<Map<String, PendingOpen>>(emptyMap())

    private val nowMs = monotonicMs()

    private data class StreamKey(val relayPeer: String, val id: Long)

    /** An `OPEN` with the [window] of credit it gives the responder. */
    private class PendingOpen(val id: Long, val window: Int, val receivedAt: Long)

    /** Routes forwarded frames to their streams until cancelled: run it while the client runs. */
    suspend fun run() {
        merge(
            client.forwarded.map { Event.Frame(it) },
            client.presence.map { Event.Presence(it) },
            client.state.map { Event.State(it) },
            client.errors.map { Event.Error(it) },
        ).collect { event ->
            when (event) {
                is Event.Frame -> onFrame(event.frame)
                is Event.Presence -> {
                    closeWhere { it.relayPeer !in event.peers }
                    opens.update { map -> map.filterKeys { it in event.peers } }
                }

                is Event.State -> onState(event.state)

                is Event.Error -> if (event.error == RelayError.RateLimited) closeWhere { true }
            }
        }
    }

    /** This device's relay id as [run] last saw it; touched by [run] only. */
    private var joinedAs: String? = null

    private var seenState = false

    private fun onState(state: RelayState) {
        // A new connection, even one that replaced the old one unseen, gave this device
        // a new relay id: the old streams lead nowhere.
        val self = (state as? RelayState.Joined)?.self
        if (self == null || seenState && self != joinedAs) {
            closeWhere { true }
            opens.value = emptyMap()
        }
        seenState = true
        joinedAs = self
    }

    /**
     * Opens a stream to the device [peerId]: the [ConnectRole.INITIATOR] asks, the
     * [ConnectRole.RESPONDER] waits for the ask. Both sides must call it.
     *
     * @throws TransportException when the device is not in the room or does not answer.
     */
    override suspend fun connect(peerId: String, role: ConnectRole): ByteStream {
        val relayPeer = resolveRelayPeer(peerId)
            ?: throw TransportException("The device is not in the relay room")
        return when (role) {
            ConnectRole.INITIATOR -> initiate(relayPeer)
            ConnectRole.RESPONDER -> respond(relayPeer)
        }
    }

    private suspend fun initiate(relayPeer: String): ByteStream {
        val id = randomBytes(STREAM_ID_BYTES).readBigEndian(0, STREAM_ID_BYTES)
        val stream = register(StreamKey(relayPeer, id))
        val open = streamFrame(TYPE_OPEN, id, countBytes(stream.openWindow()))
        var accepted = false
        try {
            accepted = withTimeoutOrNull(connectTimeoutMs) {
                while (!stream.accepted.isCompleted) {
                    client.forward(relayPeer, open)
                    withTimeoutOrNull(OPEN_RESEND_MS) { stream.accepted.await() }
                }
                // Throws if the relay connection ended instead.
                stream.accepted.await()
                true
            } ?: false
        } finally {
            if (!accepted) {
                drop(stream)
                // The responder may have accepted it already, or may still find the open.
                withContext(NonCancellable) {
                    withTimeoutOrNull(CLOSE_TIMEOUT_MS) { forwardQuietly(relayPeer, streamFrame(TYPE_CLOSE, id)) }
                }
            }
        }
        if (!accepted) throw TransportException("The device did not accept the relay stream")
        return stream
    }

    private suspend fun respond(relayPeer: String): ByteStream {
        val open = withTimeoutOrNull(connectTimeoutMs) {
            opens
                .map { map -> map[relayPeer]?.takeIf { nowMs() - it.receivedAt <= OPEN_FRESH_MS } }
                .filterNotNull()
                .first()
        } ?: throw TransportException("The device did not open a relay stream")
        opens.update { it - relayPeer }
        val stream = register(StreamKey(relayPeer, open.id))
        stream.grant(open.window.toLong())
        stream.accepted.complete(Unit)
        var sent = false
        try {
            client.forward(relayPeer, streamFrame(TYPE_ACCEPT, open.id, countBytes(stream.openWindow())))
            sent = true
        } finally {
            if (!sent) drop(stream)
        }
        return stream
    }

    private suspend fun onFrame(frame: RelayForwardFrame) {
        val payload = frame.payload
        if (payload.size < HEADER_SIZE) return
        val key = StreamKey(frame.from, payload.readBigEndian(1, STREAM_ID_BYTES))
        val data = payload.copyOfRange(HEADER_SIZE, payload.size)
        val stream = streams.value[key]
        when (payload[0]) {
            TYPE_OPEN -> if (stream == null) {
                val open = PendingOpen(key.id, window = data.readCount(), receivedAt = nowMs())
                opens.update { it + (frame.from to open) }
            } else {
                // The accept crossed a repeated open, or was dropped.
                forwardQuietly(frame.from, streamFrame(TYPE_ACCEPT, key.id, countBytes(stream.initialWindow)))
            }

            TYPE_ACCEPT -> stream?.accept(data.readCount())
            TYPE_DATA -> stream?.let {
                if (!it.deliver(data)) {
                    // Sent past its credit: break the stream rather than hold up the
                    // relay connection for it.
                    drop(it)
                    forwardQuietly(frame.from, streamFrame(TYPE_CLOSE, key.id))
                }
            }

            TYPE_CREDIT -> stream?.grant(data.readCount().toLong())
            TYPE_CLOSE -> if (stream != null) {
                drop(stream)
            } else {
                // An attempt the initiator gave up on before it was accepted.
                opens.update { map -> map.filterNot { (peer, open) -> peer == key.relayPeer && open.id == key.id } }
            }
        }
    }

    private fun register(key: StreamKey): RelayForwardStream {
        val pool = client.forwardCredit
            ?: throw TransportException("Not connected to the relay")
        val stream = RelayForwardStream(key, pool)
        streams.update { it + (key to stream) }
        return stream
    }

    /** Forgets the [stream], then [RelayForwardStream.finish]es it. */
    private fun drop(stream: RelayForwardStream) {
        streams.update { it - stream.key }
        stream.finish()
    }

    private fun closeWhere(predicate: (StreamKey) -> Boolean) {
        val closing = streams.value.filterKeys(predicate)
        streams.update { it - closing.keys }
        closing.values.forEach { it.finish() }
    }

    private suspend fun forwardQuietly(relayPeer: String, payload: ByteArray) {
        try {
            client.forward(relayPeer, payload)
        } catch (expected: RelayException) {
            // The relay connection is gone, and with it the stream.
        }
    }

    /** One end of a relay-forwarded stream; it holds credit of [pool] until it finishes. */
    private inner class RelayForwardStream(
        val key: StreamKey,
        private val pool: RelayForwardCredit,
    ) : ByteStream {
        val accepted = CompletableDeferred<Unit>()

        /** What the peer sent and the reader has not taken yet. */
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

        /** `DATA` frames this end may still send, or [CLOSED]. */
        private val credit = MutableStateFlow(0)

        /** Guards the receive credit. */
        private val lock = SynchronizedObject()

        /** `DATA` frames the peer may still send to this end. */
        private var peerCredit = 0

        /** `DATA` frames that arrived and the reader has not taken. */
        private var unread = 0

        private var finished = false

        /** The first credit this end gave the peer, in its `OPEN` or `ACCEPT`. */
        var initialWindow = 0
            private set

        init {
            pool.register()
        }

        /** Takes the first credit for the peer from the pool; returns it, to name in `OPEN` or `ACCEPT`. */
        fun openWindow(): Int = synchronized(lock) {
            val grant = if (finished) 0 else pool.take(pool.share())
            peerCredit += grant
            initialWindow = grant
            grant
        }

        /** The peer accepted the stream, with [window] credit for this end. */
        fun accept(window: Int) {
            if (accepted.complete(Unit)) grant(window.toLong())
        }

        /** Takes a message from the peer; `false` when it sent past its credit. */
        fun deliver(bytes: ByteArray): Boolean = synchronized(lock) {
            when {
                // What arrives after this end closed is dropped.
                finished -> true
                peerCredit == 0 -> false
                else -> {
                    peerCredit -= 1
                    unread += 1
                    inbox.trySend(bytes)
                    true
                }
            }
        }

        /** Adds credit the peer gave; it never adds up to more than [MAX_WINDOW]. */
        fun grant(count: Long) {
            if (count <= 0L) return
            credit.update { current ->
                if (current == CLOSED) current else (current + count).coerceAtMost(MAX_WINDOW.toLong()).toInt()
            }
        }

        /** Gives the credit of this end back to the pool; once. */
        fun finish() {
            val held = synchronized(lock) {
                if (finished) return@synchronized null
                finished = true
                peerCredit + unread
            }
            held?.let(pool::unregister)
            credit.value = CLOSED
            accepted.completeExceptionally(TransportException("The relay stream closed"))
            inbox.close()
        }

        override suspend fun read(): ByteArray? {
            val bytes = receive().getOrNull() ?: return null
            synchronized(lock) {
                if (!finished) {
                    unread -= 1
                    pool.release(1)
                }
            }
            topUp()
            return bytes
        }

        /** Waits for the next message; while the peer holds no credit, asks the pool for some again. */
        private suspend fun receive(): ChannelResult<ByteArray> {
            while (true) {
                topUp()
                val starved = synchronized(lock) { !finished && peerCredit + unread == 0 }
                if (!starved) return inbox.receiveCatching()
                withTimeoutOrNull(STARVED_RETRY_MS) { inbox.receiveCatching() }?.let { return it }
            }
        }

        /** Gives the peer credit up to the share of this stream, once it holds half of it or less. */
        private suspend fun topUp() {
            val grant = synchronized(lock) {
                val share = pool.share()
                val held = peerCredit + unread
                if (finished || held > share / 2) {
                    0
                } else {
                    pool.take(share - held).also { peerCredit += it }
                }
            }
            if (grant > 0) forwardQuietly(key.relayPeer, streamFrame(TYPE_CREDIT, key.id, countBytes(grant)))
        }

        override suspend fun write(bytes: ByteArray) {
            require(bytes.size <= MAX_MESSAGE_SIZE) { "A message is at most $MAX_MESSAGE_SIZE bytes" }
            // Waits until the peer has room for one more message, then takes it.
            do {
                val current = credit.first { it != 0 }
                if (current == CLOSED || key !in streams.value) {
                    throw TransportException("The relay stream is closed")
                }
            } while (!credit.compareAndSet(current, current - 1))
            try {
                client.forward(key.relayPeer, streamFrame(TYPE_DATA, key.id, bytes))
            } catch (e: RelayException) {
                throw TransportException("The relay stream is closed", e)
            }
        }

        override suspend fun awaitWritable() {
            // The peer's reader first, then the relay connection.
            credit.first { it != 0 }
            client.awaitWritable()
        }

        override suspend fun close() {
            if (key !in streams.value) return
            drop(this)
            // Best effort: a queue that never drains, such as of a lost connection, holds no
            // close for good; the peer's stream times out then.
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { forwardQuietly(key.relayPeer, streamFrame(TYPE_CLOSE, key.id)) }
        }
    }

    private sealed interface Event {
        class Frame(val frame: RelayForwardFrame) : Event

        class Presence(val peers: Set<String>) : Event

        class State(val state: RelayState) : Event

        class Error(val error: RelayError) : Event
    }
}

private const val TYPE_OPEN: Byte = 1
private const val TYPE_ACCEPT: Byte = 2
private const val TYPE_DATA: Byte = 3
private const val TYPE_CLOSE: Byte = 4
private const val TYPE_CREDIT: Byte = 5

private const val STREAM_ID_BYTES = 8
private const val HEADER_SIZE = 1 + STREAM_ID_BYTES

private const val COUNT_BYTES = 4

/** Returns [count] as a big-endian `u32`. */
private fun countBytes(count: Int): ByteArray =
    ByteArray(COUNT_BYTES).apply { writeBigEndian(0, COUNT_BYTES, count.toLong()) }

/** Reads a `u32` count; a malformed one is none, and one past [Int.MAX_VALUE] is that. */
private fun ByteArray.readCount(): Int =
    if (size == COUNT_BYTES) readBigEndian(0, COUNT_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0

/** Returns `type | id | data`, the id big-endian. */
private fun streamFrame(type: Byte, id: Long, data: ByteArray = ByteArray(0)): ByteArray {
    val out = ByteArray(HEADER_SIZE + data.size)
    out[0] = type
    out.writeBigEndian(1, STREAM_ID_BYTES, id)
    data.copyInto(out, HEADER_SIZE)
    return out
}
