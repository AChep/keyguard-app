package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.util.ReconnectBackoff
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/** An open WebSocket to the relay. */
internal interface RelayWire {
    val incoming: ReceiveChannel<Frame>

    suspend fun send(frame: Frame)

    /** Why the relay closed the WebSocket, once [incoming] ended. */
    suspend fun closeReason(): CloseReason?
}

/** Opens WebSockets to one relay. */
internal fun interface RelayDialer {
    /**
     * Opens a WebSocket, runs [session] on it and closes it. Returns what [session]
     * returned, or why the relay refused the WebSocket.
     */
    suspend fun dial(session: suspend (RelayWire) -> RelayError?): RelayError?
}

/**
 * The connections to one relay. The rooms opened through it share a WebSocket and its
 * peer id, up to the rooms per connection of the relay's welcome and at most
 * [MAX_ROOMS_PER_SOCKET]; more rooms, or a second client of the same room, open another
 * WebSocket. A WebSocket opens when its first room runs and closes when its last room
 * stops.
 *
 * A WebSocket starts with the relay's `welcome`, which names its peer id and limits; a
 * relay this build can not use is [RelayError.Incompatible]. It joins all its rooms then,
 * and reconnects with a jittered 1 s → 60 s backoff, under the new peer id the relay
 * draws. A refused join waits out a backoff of its own room, on the same WebSocket.
 * Outgoing messages of all its rooms queue together and leave at the pace of the limits,
 * see [RelayPace]. It pings the relay well within its idle timeout, see [pingIntervalMs],
 * and reconnects when the relay stops answering.
 *
 * The reader waits for the collectors of each room, so a room that is not collected holds
 * up the other rooms of its WebSocket until it stops.
 */
internal class RelayHub(
    private val dialer: RelayDialer,
    /** Runs the WebSockets. */
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = monotonicMs(),
) {
    companion object {
        /**
         * The most rooms this client puts on one WebSocket, whatever the relay allows: they
         * share its budgets and its frame queue.
         */
        const val MAX_ROOMS_PER_SOCKET = 16

        /**
         * The most peers a room may hold, this device included, whatever the relay allows:
         * the relay names the peers, so it could otherwise make a room hold any number.
         */
        const val MAX_PEERS_PER_ROOM = 64

        /** The longest pause between two pings. */
        const val PING_INTERVAL_MS = 20_000L

        private const val MIN_PING_INTERVAL_MS = 1_000L

        private const val PINGS_PER_IDLE_TIMEOUT = 3

        private const val MILLIS_PER_SECOND = 1_000L

        /** A welcome, or the answer to a join, the relay does not send for this long ends the connection. */
        const val JOIN_TIMEOUT_MS = 15_000L

        private const val CLOSE_IDLE: Short = 1000
        private const val CLOSE_POLICY: Short = 1008
        private const val CLOSE_TOO_BIG: Short = 1009
        private const val CLOSE_TRY_AGAIN: Short = 1013

        /** The errors that refuse a join when they name a room. */
        private val JOIN_REFUSALS = setOf(
            RelayError.RoomFull,
            RelayError.RateLimited,
            RelayError.TooManyRooms,
        )

        /** The errors after which the relay closes the connection. */
        private val CLOSING_ERRORS = setOf(
            RelayError.Unauthorized,
            RelayError.BadFrame,
            RelayError.FrameTooBig,
        )

        /**
         * How often a connection pings a relay with [limits]: three times per idle timeout,
         * at most every [PING_INTERVAL_MS]. No frame for two and a half times as long, pongs
         * included, ends the connection.
         */
        fun pingIntervalMs(limits: RelayLimits): Long =
            (limits.idleSeconds * MILLIS_PER_SECOND / PINGS_PER_IDLE_TIMEOUT)
                .coerceIn(MIN_PING_INTERVAL_MS, PING_INTERVAL_MS)

        /** Pings in a row the relay may leave unanswered, and half of the next one. */
        private const val PINGS_PER_SILENCE = 2.5

        /** No frame for this long, pongs included, ends a connection that pings every [pingIntervalMs]. */
        fun silenceTimeoutMs(pingIntervalMs: Long): Long = (pingIntervalMs * PINGS_PER_SILENCE).toLong()
    }

    /** Guards the state of every socket, link and seat, and the placement of the clients. */
    private val lock = SynchronizedObject()

    private val sockets = mutableListOf<Socket>()

    /** Returns a connection to the room [room]; it joins once it runs. */
    fun open(room: String): KeyguardRelayConnection {
        val client = RelayRoomClient(room)
        return KeyguardRelayConnection(
            client = client,
            run = { keep(client) },
        )
    }

    /** Keeps [client] in its room until cancelled. */
    private suspend fun keep(client: RelayRoomClient): Nothing {
        synchronized(lock) {
            client.attached = Job()
            place(client)
        }
        try {
            awaitCancellation()
        } finally {
            synchronized(lock) {
                sockets.firstOrNull { it.clients[client.room] === client }?.remove(client)
                client.disconnect(RelayState.Disconnected(null))
                client.attached.complete()
            }
        }
    }

    /** Puts [client] on a socket that has room for it, opening one if none has; under [lock]. */
    private fun place(client: RelayRoomClient) {
        val socket = sockets.firstOrNull { it.accepts(client.room) }
            ?: Socket().also { socket ->
                sockets += socket
                socket.job = scope.launch { socket.run() }
            }
        socket.add(client)
    }

    private fun closeError(reason: CloseReason?): RelayError = when (reason?.code) {
        CLOSE_IDLE -> RelayError.Timeout
        CLOSE_POLICY -> RelayError.BadFrame
        CLOSE_TOO_BIG -> RelayError.FrameTooBig
        CLOSE_TRY_AGAIN -> RelayError.SlowConsumer
        else -> RelayError.Network
    }

    /** One WebSocket, reconnected while it has rooms; its state is under [lock]. */
    private inner class Socket {
        /** The rooms of this socket, by room id. */
        val clients = mutableMapOf<String, RelayRoomClient>()

        /** The rooms the relay lets one connection join. */
        var capacity = MAX_ROOMS_PER_SOCKET

        /** The open connection. */
        var link: Link? = null

        var job: Job? = null

        /** Spaces the connections; reset by a join. */
        val backoff = ReconnectBackoff()

        /** Cuts the wait for the next connection short. */
        private val wake = Channel<Unit>(Channel.CONFLATED)

        fun accepts(room: String): Boolean = clients.size < capacity && room !in clients

        fun add(client: RelayRoomClient) {
            clients[client.room] = client
            val link = link
            if (link != null) {
                link.join(client)
            } else {
                client.stateSink.value = RelayState.Connecting
                // A new room does not wait out the backoff of a failed connection.
                wake.trySend(Unit)
            }
        }

        fun remove(client: RelayRoomClient) {
            clients.remove(client.room)
            link?.leave(client.room)
            if (clients.isEmpty()) {
                sockets -= this
                job?.cancel()
            }
        }

        /** Takes the rooms per connection of [limits]; the rooms over it move to other sockets. */
        fun adopt(limits: RelayLimits) {
            capacity = limits.roomsPerConnection.coerceAtMost(MAX_ROOMS_PER_SOCKET)
            clients.values.drop(capacity).forEach { client ->
                remove(client)
                place(client)
            }
        }

        suspend fun run(): Nothing {
            while (true) {
                val error = connectOnce()
                synchronized(lock) {
                    clients.values.forEach { it.disconnect(RelayState.Disconnected(error)) }
                }
                withTimeoutOrNull(backoff.nextDelayMs()) { wake.receive() }
            }
        }

        /** Runs one connection; returns why it ended. */
        private suspend fun connectOnce(): RelayError? {
            wake.tryReceive()
            synchronized(lock) {
                clients.values.forEach { it.stateSink.value = RelayState.Connecting }
            }
            // ktor may end a broken session with a CancellationException of its own.
            return runCatchingUnlessCancelled {
                dialer.dial { wire -> session(wire) }
            }.getOrElse { e ->
                (e as? RelayException)?.error ?: RelayError.Network
            }
        }

        private suspend fun session(wire: RelayWire): RelayError? {
            val welcome = when (val greeting = greet(wire)) {
                is Greeting.Welcomed -> greeting.welcome
                is Greeting.Refused -> return greeting.error
            }
            val limits = welcome.limits
            val outbox = RelayOutbox()
            try {
                return coroutineScope {
                    val link = Link(
                        socket = this@Socket,
                        self = welcome.peer,
                        limits = limits,
                        outbox = outbox,
                        credit = RelayForwardCredit.of(limits),
                        sessionScope = this,
                    )
                    val reader = Reader(this@Socket, link)
                    val bucket = TokenBucket(RelayPace.of(limits), nowMs)
                    val pingIntervalMs = pingIntervalMs(limits)
                    val silenceTimeoutMs = silenceTimeoutMs(pingIntervalMs)
                    launch { link.write(wire, bucket) }
                    launch {
                        while (true) {
                            delay(pingIntervalMs)
                            if (nowMs() - reader.lastFrameAt > silenceTimeoutMs) {
                                throw RelayException(RelayError.Timeout)
                            }
                            outbox.send(Frame.Text(RelayCodec.PING))
                        }
                    }
                    synchronized(lock) {
                        adopt(limits)
                        this@Socket.link = link
                        clients.values.forEach(link::join)
                    }
                    try {
                        reader.read(wire)
                    } finally {
                        synchronized(lock) {
                            this@Socket.link = null
                            clients.values.forEach { it.outbox = null }
                        }
                        coroutineContext.cancelChildren()
                    }
                }
            } finally {
                // Only now: the writer and the ping are done with it.
                outbox.close()
            }
        }

        /** Reads the `welcome` the relay sends first; anything else first ends the connection. */
        private suspend fun greet(wire: RelayWire): Greeting = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            for (frame in wire.incoming) {
                val first = when (frame) {
                    is Frame.Text -> RelayCodec.decodeText(frame.readText())
                    is Frame.Binary -> null
                    else -> continue
                }
                return@withTimeoutOrNull when {
                    first is RelayServerFrame.Welcome && first.isCompatible -> Greeting.Welcomed(first)
                    // Without the token, the relay says so and closes the connection.
                    first is RelayServerFrame.Err -> Greeting.Refused(first.error)
                    else -> Greeting.Refused(RelayError.Incompatible)
                }
            }
            Greeting.Refused(closeError(wire.closeReason()))
        } ?: Greeting.Refused(RelayError.Timeout)
    }

    /** How the first frame of a connection went. */
    private sealed interface Greeting {
        class Welcomed(val welcome: RelayServerFrame.Welcome) : Greeting

        class Refused(val error: RelayError) : Greeting
    }

    /** A room of a [Link]: joined, or waiting for the answer to its join. */
    private class Seat(val client: RelayRoomClient) {
        var joined = false
    }

    /** One connection of a [Socket]: its peer id, its queues and its seats; under [lock]. */
    private inner class Link(
        val socket: Socket,
        /** The peer id the relay drew for this connection. */
        val self: String,
        val limits: RelayLimits,
        val outbox: RelayOutbox,
        val credit: RelayForwardCredit,
        /** The scope of the connection; its timers end with it. */
        private val sessionScope: CoroutineScope,
    ) {
        /** The most other peers a room of this connection holds, see [MAX_PEERS_PER_ROOM]. */
        val maxOtherPeers: Int = limits.peersPerRoom.coerceIn(2, MAX_PEERS_PER_ROOM) - 1

        /** Joins and leaves; they go out ahead of [outbox]. */
        private val control = Channel<Frame>(Channel.UNLIMITED)

        /** The seat of each room that joined or asked to. */
        private val seats = mutableMapOf<String, Seat>()

        /** Per room, the answers to skip: those to the joins of clients that left since. */
        private val abandoned = mutableMapOf<String, Int>()

        fun join(client: RelayRoomClient) {
            if (client.room in seats) return
            val seat = Seat(client)
            seats[client.room] = seat
            control.trySend(Frame.Text(RelayCodec.join(client.room)))
            client.stateSink.value = RelayState.Connecting
            sessionScope.launch {
                delay(JOIN_TIMEOUT_MS)
                // The relay answers joins in order, so it stopped answering.
                val unanswered = synchronized(lock) { seats[client.room] === seat && !seat.joined }
                if (unanswered) throw RelayException(RelayError.Timeout)
            }
        }

        /** Joins [client] again after [delayMs], if it is still here and not joined. */
        fun rejoin(client: RelayRoomClient, delayMs: Long) {
            sessionScope.launch {
                delay(delayMs)
                synchronized(lock) {
                    if (socket.link === this@Link && socket.clients[client.room] === client) join(client)
                }
            }
        }

        fun leave(room: String) {
            val seat = seats.remove(room) ?: return
            if (!seat.joined) abandoned[room] = (abandoned[room] ?: 0) + 1
            control.trySend(Frame.Text(RelayCodec.leave(room)))
        }

        /**
         * Takes the answer to the oldest unanswered join of [room]: returns the seat that
         * waits for it, or `null` if its client left.
         */
        fun answer(room: String): Seat? {
            val skip = abandoned[room] ?: 0
            when {
                skip > 1 -> abandoned[room] = skip - 1
                skip == 1 -> abandoned -= room
            }
            return seats[room]?.takeIf { skip == 0 && !it.joined }
        }

        /** Frees the seat of a refused join. */
        fun vacate(room: String) {
            seats -= room
        }

        fun joinedClient(room: String): RelayRoomClient? = seats[room]?.takeIf { it.joined }?.client

        fun joinedClients(): List<RelayRoomClient> = seats.values.filter { it.joined }.map { it.client }

        suspend fun write(wire: RelayWire, bucket: TokenBucket) {
            while (true) {
                select<Unit> {
                    control.onReceive { frame ->
                        bucket.acquire(frame.data.size)
                        wire.send(frame)
                    }
                    outbox.frames.onReceive { frame ->
                        bucket.acquire(frame.data.size)
                        wire.send(frame)
                        outbox.queued.update { it - 1 }
                    }
                }
            }
        }
    }

    /** Reads the frames of one [Link]. */
    private inner class Reader(
        private val socket: Socket,
        private val link: Link,
    ) {
        @Volatile
        var lastFrameAt = nowMs()
            private set

        /** The error the relay sent before it closed the connection. */
        private var closingError: RelayError? = null

        /** Reads until the connection ends; returns why it did. */
        suspend fun read(wire: RelayWire): RelayError? {
            for (frame in wire.incoming) {
                lastFrameAt = nowMs()
                when (frame) {
                    is Frame.Text -> onText(frame.readText())
                    // The codec copies out what it keeps.
                    is Frame.Binary -> RelayCodec.decodeBinary(frame.data)?.let { onForward(it) }
                    else -> Unit
                }
            }
            return closingError ?: closeError(wire.closeReason())
        }

        /** Handles one text frame; a frame this build does not know is ignored. */
        private suspend fun onText(text: String) {
            when (val frame = RelayCodec.decodeText(text)) {
                is RelayServerFrame.Joined -> synchronized(lock) { onJoined(frame) }
                is RelayServerFrame.Presence -> synchronized(lock) {
                    link.joinedClient(frame.room)?.presenceSink?.update { peers ->
                        when {
                            !frame.joined -> peers - frame.peer
                            // A room never holds more peers than the relay said it would.
                            frame.peer in peers || peers.size >= link.maxOtherPeers -> peers
                            else -> peers + frame.peer
                        }
                    }
                }

                is RelayServerFrame.Msg -> synchronized(lock) { link.joinedClient(frame.room) }
                    ?.deliver(frame.message)

                is RelayServerFrame.Err -> onError(frame.error, frame.room)
                // Only the first frame is a welcome.
                is RelayServerFrame.Welcome,
                RelayServerFrame.Pong,
                null,
                -> Unit
            }
        }

        /**
         * Hands [frame] to the room it names, if its sender is in that room. A peer id is
         * unique only within a room, so a frame never reaches another room.
         */
        private suspend fun onForward(frame: RelayForwardFrame) {
            val client = synchronized(lock) {
                link.joinedClient(frame.room)?.takeIf { frame.from in it.presence.value }
            }
            client?.deliver(frame)
        }

        private fun onJoined(frame: RelayServerFrame.Joined) {
            val seat = link.answer(frame.room) ?: return
            seat.joined = true
            val client = seat.client
            // The join and the peers first: the frames of the room follow.
            client.presenceSink.value = frame.peers.take(link.maxOtherPeers).toSet()
            client.outbox = link.outbox
            client.credit = link.credit
            client.stateSink.value = RelayState.Joined(link.self, frame.ticket, link.limits)
            client.backoff.reset()
            socket.backoff.reset()
        }

        private fun onError(error: RelayError, room: String?) {
            if (error in CLOSING_ERRORS) closingError = error
            synchronized(lock) {
                when {
                    room == null -> socket.clients.values.forEach { it.errorSink.tryEmit(error) }
                    error in JOIN_REFUSALS -> onRefused(error, room)
                    else -> socket.clients[room]?.errorSink?.tryEmit(error)
                }
            }
        }

        /**
         * Handles the refusal of a join of [room]. A full relay, or an address in as many
         * rooms as the relay allows, is no reason for another WebSocket: the room waits out
         * a backoff on this one.
         */
        private fun onRefused(error: RelayError, room: String) {
            val client = link.answer(room)?.client ?: return
            link.vacate(room)
            client.errorSink.tryEmit(error)
            val joined = link.joinedClients().size
            if (error == RelayError.TooManyRooms && joined > 0) {
                // The relay allows fewer rooms per connection than it said: the rest move.
                socket.capacity = joined
                socket.remove(client)
                place(client)
            } else {
                client.disconnect(RelayState.Disconnected(error))
                link.rejoin(client, client.backoff.nextDelayMs())
            }
        }
    }
}

/** The outgoing queue of one connection, shared by its rooms. */
internal class RelayOutbox {
    private companion object {
        const val CAPACITY = 32

        /** [awaitWritable] returns once fewer frames than this wait. */
        const val LOW_WATER = 8
    }

    /** The frames sent and not yet written. */
    val queued = MutableStateFlow(0)

    /** A frame that never reaches the writer, such as one of a cancelled [send], is not queued. */
    val frames = Channel<Frame>(CAPACITY) {
        queued.update { n -> (n - 1).coerceAtLeast(0) }
    }

    suspend fun send(frame: Frame) {
        queued.update { it + 1 }
        try {
            frames.send(frame)
        } catch (e: ClosedSendChannelException) {
            throw RelayException(RelayError.NotConnected, e)
        }
    }

    suspend fun awaitWritable() {
        queued.first { it < LOW_WATER }
    }

    fun close() {
        frames.close()
        queued.value = 0
    }
}

/** A [RelayClient] for one room of a [RelayHub]. Its state is written under the hub's lock. */
internal class RelayRoomClient(
    /** The 64-hex room id. */
    val room: String,
) : RelayClient {
    private companion object {
        const val INBOUND_CAPACITY = 64

        const val ERROR_BUFFER = 16
    }

    val stateSink = MutableStateFlow<RelayState>(RelayState.Disconnected(null))
    val presenceSink = MutableStateFlow(emptySet<String>())
    private val messageChannel = Channel<RelayMessage>(INBOUND_CAPACITY)
    private val forwardChannel = Channel<RelayForwardFrame>(INBOUND_CAPACITY)
    val errorSink = MutableSharedFlow<RelayError>(
        extraBufferCapacity = ERROR_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Spaces the joins the relay refused; reset by a join. */
    val backoff = ReconnectBackoff()

    /** The queue of the connection this room is joined on. */
    @Volatile
    var outbox: RelayOutbox? = null

    /** The forwarding credit of the connection this room is joined on. */
    @Volatile
    var credit: RelayForwardCredit? = null

    /** Completes when `run` stops. */
    @Volatile
    var attached: CompletableJob = Job().apply { complete() }

    override val state: StateFlow<RelayState> = stateSink.asStateFlow()

    override val presence: StateFlow<Set<String>> = presenceSink.asStateFlow()

    override val messages: Flow<RelayMessage> = messageChannel.receiveAsFlow()

    override val forwarded: Flow<RelayForwardFrame> = forwardChannel.receiveAsFlow()

    override val errors: SharedFlow<RelayError> = errorSink.asSharedFlow()

    override val forwardCredit: RelayForwardCredit? get() = credit

    /** The largest message the relay of the current connection takes. */
    private val maxMessageBytes: Int
        get() = (stateSink.value as? RelayState.Joined)?.limits?.maxMessageBytes ?: RelayCodec.MAX_MESSAGE_BYTES

    override suspend fun publish(to: String, data: ByteArray) {
        require(to == RelayCodec.BROADCAST || RelayCodec.isPeerId(to)) { "Not a relay peer id" }
        val text = RelayCodec.pub(room, to, data)
        // The frame is ASCII: a character is a byte.
        if (text.length > maxMessageBytes) throw RelayException(RelayError.FrameTooBig)
        enqueue(Frame.Text(text))
    }

    override suspend fun forward(to: String, payload: ByteArray) {
        require(RelayCodec.isPeerId(to)) { "Not a relay peer id" }
        if (payload.size > maxMessageBytes - RelayCodec.FORWARD_HEADER_SIZE) {
            throw RelayException(RelayError.FrameTooBig)
        }
        enqueue(Frame.Binary(true, RelayCodec.forward(room, to, payload)))
    }

    override suspend fun awaitWritable() {
        outbox?.awaitWritable()
    }

    private suspend fun enqueue(frame: Frame) {
        val current = outbox ?: throw RelayException(RelayError.NotConnected)
        current.send(frame)
    }

    /** Marks the room not joined, with [state]. */
    fun disconnect(state: RelayState) {
        outbox = null
        credit = null
        presenceSink.value = emptySet()
        stateSink.value = state
    }

    suspend fun deliver(message: RelayMessage) = offer(messageChannel, message)

    suspend fun deliver(frame: RelayForwardFrame) = offer(forwardChannel, frame)

    /** Waits for room in [channel], unless `run` stops first and the value is dropped. */
    private suspend fun <T> offer(channel: SendChannel<T>, value: T) {
        val attached = attached
        select<Unit> {
            channel.onSend(value) {}
            attached.onJoin {}
        }
    }
}
