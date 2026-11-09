package com.artemchep.keyguard.provider.keyguard.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Collections
import kotlin.random.Random

/**
 * TEST ONLY: a relay in memory, with any number of rooms and the relay's delivery rules.
 * The clients of its [connector] behave like the real one: the relay draws a fresh peer id
 * for each connection, a client sees its join before the others see it, and rejoins under
 * a new id after a drop. With [reachable] off, they fail to connect as without a network.
 * A test may also [join][TestRelayRoom.join] a room by hand.
 *
 * With a [mailbox], the relay also keeps items for the devices that are offline: its
 * welcome names the limits of the items, and each connection gets a ticket, see
 * [mailboxes].
 */
class TestRelay(
    private val random: Random = Random(7),
    limits: RelayLimits = RelayLimits(),
    /** The items the relay keeps, or `null` for a relay that keeps none. */
    val mailbox: TestRelayMailbox? = null,
) {
    /** The limits of the relay's welcome. */
    val limits: RelayLimits = if (mailbox != null) {
        limits.copy(mailbox = limits.mailbox ?: RelayMailboxLimits())
    } else {
        limits
    }

    /** The items the relay keeps, as a device reaches them. */
    val mailboxes: KeyguardRelayMailboxes = KeyguardRelayMailboxes { endpoint ->
        mailbox ?: KeyguardRelayMailboxes.NONE.get(endpoint)
    }

    private val lock = Any()

    private val rooms = mutableMapOf<String, TestRelayRoom>()

    /** Every client the [connector] made, in order. */
    val clients: MutableList<TestRelayClient> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var reachable: Boolean = true

    /** Why a connection fails while not [reachable]. */
    @Volatile
    var refusal: RelayError = RelayError.Network

    /** Connections never get past connecting, as behind a black hole. */
    @Volatile
    var stalled: Boolean = false

    val connector: KeyguardRelayConnector = KeyguardRelayConnector { _, spec ->
        val client = TestRelayClient(room(spec.id))
        clients += client
        KeyguardRelayConnection(
            client = client,
            run = { client.run() },
        )
    }

    /** The room [id]. */
    fun room(id: String = "room"): TestRelayRoom = synchronized(lock) {
        rooms.getOrPut(id) { TestRelayRoom(this, id) }
    }

    internal fun newPeerId(): String = synchronized(lock) {
        random.nextBytes(RelayCodec.PEER_ID_BYTES).toHexString()
    }

    /** A new ticket of a connection, live until [revokeTicket]; `null` if the relay keeps no items. */
    internal fun issueTicket(): String? {
        val mailbox = mailbox ?: return null
        val ticket = synchronized(lock) { random.nextBytes(RelayCodec.TICKET_BYTES).toHexString() }
        mailbox.tickets += ticket
        return ticket
    }

    internal fun revokeTicket(ticket: String?) {
        if (ticket != null) mailbox?.tickets?.remove(ticket)
    }
}

/**
 * TEST ONLY: one room of a [TestRelay]; it forwards frames only between its own members.
 * As on the relay, a member sees a peer join before any message of it, and its own join
 * before any message to it: a join and the deliveries hold the lock of the room.
 */
class TestRelayRoom internal constructor(
    internal val relay: TestRelay,
    /** The room id, as forwarded frames name it. */
    val id: String,
) {
    private val lock = Any()

    /** Relay peer id → client. */
    private val members = mutableMapOf<String, TestRelayClient>()

    /** Every message published in the room, by target, as the relay saw it. */
    val published: MutableList<Pair<String, RelayMessage>> = Collections.synchronizedList(mutableListOf())

    /** Joins a new client under a fresh relay peer id. It stays until it [leave]s. */
    fun join(): TestRelayClient = TestRelayClient(this).also(::enter)

    /** Takes [client] out of the room, as a closed connection does. */
    fun leave(client: TestRelayClient) {
        synchronized(lock) {
            val id = client.idOrNull ?: return
            members.remove(id)
            relay.revokeTicket(client.ticket)
            client.left()
            refreshPresence()
        }
    }

    /** Delivers [message] to [to] as if the relay sent it. */
    suspend fun inject(to: TestRelayClient, message: RelayMessage) {
        to.inbox.send(message)
    }

    /** Joins [client] under a fresh relay peer id, as a new connection does. */
    internal fun enter(client: TestRelayClient) {
        val id = relay.newPeerId()
        synchronized(lock) {
            // The relay answers the join before it tells the others.
            client.joined(
                id = id,
                peers = members.keys.toSet(),
                credit = RelayForwardCredit.of(relay.limits),
                ticket = relay.issueTicket(),
            )
            members[id] = client
            refreshPresence()
        }
    }

    internal fun publish(from: String, to: String, data: ByteArray) {
        val message = RelayMessage(from, data)
        published += to to message
        synchronized(lock) {
            val targets = if (to == RelayCodec.BROADCAST) {
                members.filterKeys { it != from }.values
            } else {
                listOfNotNull(members[to])
            }
            targets.forEach { it.inbox.trySend(message) }
        }
    }

    internal fun forward(from: String, to: String, payload: ByteArray) {
        synchronized(lock) {
            members[to]?.frames?.trySend(RelayForwardFrame(id, from, payload))
        }
    }

    /** Under the lock. */
    private fun refreshPresence() {
        members.forEach { (id, client) -> client.presenceSink.value = members.keys - id }
    }
}

/** TEST ONLY: a [RelayClient] in a [TestRelayRoom]. */
class TestRelayClient internal constructor(
    private val room: TestRelayRoom,
) : RelayClient {
    private companion object {
        const val RECONNECT_MS = 300L
    }

    private val stateSink = MutableStateFlow<RelayState>(RelayState.Disconnected(null))
    internal val presenceSink = MutableStateFlow(emptySet<String>())
    internal val inbox = Channel<RelayMessage>(Channel.UNLIMITED)
    internal val frames = Channel<RelayForwardFrame>(Channel.UNLIMITED)
    private val errorSink = MutableSharedFlow<RelayError>(extraBufferCapacity = 16)

    @Volatile
    private var dropped = CompletableDeferred<Unit>()

    @Volatile
    internal var idOrNull: String? = null
        private set

    @Volatile
    private var credit: RelayForwardCredit? = null

    /** The ticket of the current connection, if the relay keeps items. */
    @Volatile
    internal var ticket: String? = null
        private set

    /** The relay peer id of the current connection. */
    val id: String get() = checkNotNull(idOrNull) { "The client is not in the room." }

    override val state: StateFlow<RelayState> = stateSink.asStateFlow()
    override val presence: StateFlow<Set<String>> = presenceSink.asStateFlow()
    override val messages: Flow<RelayMessage> = inbox.receiveAsFlow()
    override val forwarded: Flow<RelayForwardFrame> = frames.receiveAsFlow()
    override val errors: SharedFlow<RelayError> = errorSink.asSharedFlow()

    override val forwardCredit: RelayForwardCredit? get() = credit

    internal fun joined(
        id: String,
        peers: Set<String>,
        credit: RelayForwardCredit,
        ticket: String? = null,
    ) {
        idOrNull = id
        this.credit = credit
        this.ticket = ticket
        presenceSink.value = peers
        stateSink.value = RelayState.Joined(id, ticket = ticket, limits = room.relay.limits)
    }

    internal fun left() {
        idOrNull = null
        credit = null
        ticket = null
        presenceSink.value = emptySet()
        stateSink.value = RelayState.Disconnected(RelayError.Network)
    }

    /** Ends the current connection of a client of the [TestRelay.connector]. */
    fun drop() {
        dropped.complete(Unit)
    }

    /** Reports [error] as the relay would. */
    suspend fun emitError(error: RelayError) {
        errorSink.emit(error)
    }

    /** Connects, and connects again after each drop, as the real client does. */
    internal suspend fun run(): Nothing {
        while (true) {
            stateSink.value = RelayState.Connecting
            if (room.relay.stalled) awaitCancellation()
            if (!room.relay.reachable) {
                stateSink.value = RelayState.Disconnected(room.relay.refusal)
                delay(RECONNECT_MS)
                continue
            }
            dropped = CompletableDeferred()
            try {
                room.enter(this)
                dropped.await()
            } finally {
                room.leave(this)
            }
            delay(RECONNECT_MS)
        }
    }

    override suspend fun publish(to: String, data: ByteArray) {
        val from = idOrNull ?: throw RelayException(RelayError.NotConnected)
        room.publish(from, to, data)
    }

    override suspend fun forward(to: String, payload: ByteArray) {
        val from = idOrNull ?: throw RelayException(RelayError.NotConnected)
        if (payload.size > room.relay.limits.maxMessageBytes - RelayCodec.FORWARD_HEADER_SIZE) {
            throw RelayException(RelayError.FrameTooBig)
        }
        room.forward(from, to, payload)
    }

    override suspend fun awaitWritable() = Unit
}
