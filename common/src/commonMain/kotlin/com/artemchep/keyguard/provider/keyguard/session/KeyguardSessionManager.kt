package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardSyncTooLarge
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.provider.keyguard.QueueP2pSync
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.activeDevices
import com.artemchep.keyguard.feature.p2psync.session.BlobExchangeConfig
import com.artemchep.keyguard.feature.p2psync.session.CloseReason
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionStateMachine
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionTimings
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.provider.keyguard.P2P_GROUP_KEY_SIZE
import com.artemchep.keyguard.provider.keyguard.noisePrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardInbox
import com.artemchep.keyguard.provider.keyguard.transport.IceServersGrant
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import com.artemchep.keyguard.provider.keyguard.transport.GroupObjects
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayMailboxes
import com.artemchep.keyguard.provider.keyguard.transport.PairMailbox
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.provider.keyguard.transport.RelayRoom
import com.artemchep.keyguard.provider.keyguard.transport.NoiseByteStream
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuite
import com.artemchep.keyguard.provider.keyguard.transport.PrefetchedStream
import com.artemchep.keyguard.provider.keyguard.transport.RelayError
import com.artemchep.keyguard.provider.keyguard.transport.RelayState
import com.artemchep.keyguard.provider.keyguard.transport.SelectedStream
import com.artemchep.keyguard.provider.keyguard.transport.TransportException
import com.artemchep.keyguard.provider.keyguard.transport.monotonicMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the session managers of a vault session share.
 *
 * @property queueSync returns the use case that queues a sync pass of a group, resolved
 *   when first needed: the sync pass hands its result to the live sessions in turn.
 * @property relayMailboxes the items each relay keeps for the devices that are offline,
 *   see [KeyguardMailbox].
 * @property relayStorageUse until when each relay may hold what this device left there,
 *   see [KeyguardMailbox.isRetiring].
 * @property objectDownloads the blob object of each group a fetch from the relay could not
 *   finish, which the next fetch resumes, see [KeyguardObjectDownloads].
 */
// A holder of the shared dependencies.
@Suppress("LongParameterList")
class KeyguardSessionDependencies(
    val roomConnector: KeyguardRoomConnector,
    val store: KeyguardSessionStore,
    val compressor: Compressor,
    val crypto: P2pCrypto,
    val inbox: KeyguardInbox,
    val queueSync: () -> QueueP2pSync,
    val timings: SyncSessionTimings = SyncSessionTimings(),
    /** The largest envelope a session sends or accepts. */
    val maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
    val now: () -> Instant = { Clock.System.now() },
    val relayMailboxes: KeyguardRelayMailboxes = KeyguardRelayMailboxes.NONE,
    val mailboxTimings: KeyguardMailboxTimings = KeyguardMailboxTimings(),
    val relayStorageUse: KeyguardRelayStorageUse = KeyguardRelayStorageUse.inMemory(),
    val objectDownloads: KeyguardObjectDownloads = KeyguardObjectDownloads(),
)

/**
 * Syncs one sync group with its other devices while [run] runs. What depends on the kind
 * of the group goes through its host, see [KeyguardSessionStore].
 *
 * It joins one relay room per active device of the roster, the room of the pair, see
 * [RelayRoom.forPair], and dials the device once it shows up: the device with the smaller
 * id initiates. Only the two devices of a pair can find its room, so a device the group
 * removed, which keeps the group key, sees none of the others. A connection is a Noise KKhfs
 * session over the first transport that opens, see [KeyguardRoom.connect], authenticated
 * by the static keys of the roster, and carries one [SyncSessionStateMachine]. A peer that
 * drops is dialed again with a backoff; a revoked peer is cut.
 *
 * A peer dials only when it holds no session with this device, so a connection it opens
 * while a session with it runs replaces that session: the newest wins, once the first
 * message of the peer, its HELLO, arrives on it. A replayed first handshake message of the
 * peer completes the handshake of this device, but only the peer can send a message that
 * decrypts, so a replay never ends a running session. A session the peer dropped without a
 * word, such as when its app was killed, never keeps its next session out until it times
 * out.
 *
 * coturn drops a connection relayed through TURN once the credentials of its allocation
 * expire. So a session over TURN ends with [CloseReason.RECONNECT] once the credentials this
 * device gave its connection reach 90% of their lifetime, and both devices connect again at
 * once, with fresh credentials, see [reconnectBeforeTurnExpires]. A direct connection never
 * needs it.
 *
 * A removed device may have been offline when it was removed, and keeps dialing the devices
 * it still knows: for a while, it is told in the room of the pair with the roster and a
 * BYE, over relay forwarding, and nothing it sends is applied, see [KeyguardRevokedPeers].
 * Once this device learns that it was removed, it dials no one, and a device that knows
 * it was removed joins no room.
 *
 * Once [closeSessions] closed the sessions for good, this device makes and takes no new
 * connection: one it took would be closed unread, while the peer, such as one that tells
 * this device it was removed, counts what it sent as delivered.
 *
 * Received envelopes and rosters go to the [KeyguardInbox], by sender. While the session is
 * live, each one queues a sync pass, and a local edit refreshes the local state; after a
 * pass the new local state goes out with [publish]. A pass asks the peers for their state
 * again with [requestResend].
 *
 * Once every device of the group allows it, the relay also keeps the state of this device
 * for each peer that is offline, and the one the peer left for it, see [KeyguardMailbox]:
 * two devices then sync without being online at the same time.
 */
// The orchestration of one group's sessions.
@Suppress("LargeClass", "TooManyFunctions")
class KeyguardSessionManager internal constructor(
    /** The id of the sync group, see [P2pGroupId]. */
    val groupId: String,
    /** The membership of this device in the sync group. */
    val membership: P2pMembership,
    private val dependencies: KeyguardSessionDependencies,
    /** `true` while someone holds the session, see [KeyguardSessionRegistry]. */
    private val live: StateFlow<Boolean>,
) {
    companion object {
        /** Local edits are coalesced for this long. */
        const val CHANGE_DEBOUNCE_MS: Long = 500L

        /** How long [awaitSettled] waits for a peer to show up in the room. */
        const val PRESENCE_GRACE_MS: Long = 3_000L

        /** The longest pause between two dials of the initiator. */
        private const val INITIATOR_MAX_RETRY_MS: Long = 15_000L

        /** The responder listens again soon after a failed attempt. */
        private const val RESPONDER_RETRY_MS: Long = 1_000L

        /** A revoked peer gets this long to receive the roster that says so. */
        private const val REVOKED_GRACE_MS: Long = KeyguardRevokedPeers.SESSION_GRACE_MS

        /**
         * How long a newer connection of a peer gets to deliver its HELLO before it may
         * replace the running session. The peer sends it right after the handshake.
         */
        const val REPLACEMENT_HELLO_TIMEOUT_MS: Long = 10_000L
    }

    private val store get() = dependencies.store

    private val crypto get() = dependencies.crypto

    /** A monotonic clock in milliseconds, for the sessions and the relay storage. */
    private val clock = monotonicMs()

    private val statusSink = MutableStateFlow(KeyguardSessionStatus())

    /** The sync status of the group. */
    val status: StateFlow<KeyguardSessionStatus> = statusSink.asStateFlow()

    /** The latest local envelope, once [run] built it; its sessions share its hash and bytes. */
    private val localEnvelope = MutableStateFlow<LocalEnvelope?>(null)

    /** The progress of each running session, by peer. */
    private val progress = MutableStateFlow<Map<String, KeyguardPeerProgress>>(emptyMap())

    /** The peers in the relay rooms. */
    private val presentPeers = MutableStateFlow<Set<String>>(emptySet())

    /** The relay state of the room of each peer, by peer. */
    private val roomStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())

    /** The path each WebRTC connection settled on, by peer. */
    private val paths = MutableStateFlow<Map<String, TransportPath>>(emptyMap())

    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    /** The sessions that run, see [closeSessions]. */
    private val running = MutableStateFlow<Set<KeyguardPeerSession>>(emptySet())

    /** `true` once [closeSessions] was called: no connection is made or taken after it. */
    private val closing = MutableStateFlow(false)

    /** Tells the removed devices that show up in their rooms so. */
    private val revokedPeers = KeyguardRevokedPeers(
        membership = membership,
        dependencies = dependencies,
        clock = clock,
        handshake = ::handshake,
        roster = { (localEnvelope.value ?: refreshLocalEnvelope()).roster },
    )

    /** The ids of the active peers in the relay room the dialers serve. */
    private val dialedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Serializes [refreshLocalEnvelope]. */
    private val refreshLock = Mutex()

    /** The authenticated streams not closed yet; [run] closes those left. */
    private val streams = KeyguardOpenStreams()

    /** What the relay keeps for the peers that are offline. */
    private val mailbox = KeyguardMailbox(
        groupId = groupId,
        membership = membership,
        dependencies = dependencies,
        localEnvelope = localEnvelope,
        progress = progress,
        onReceived = ::onReceived,
        clock = clock,
    )

    /** The roster of the local envelope: an edit of an entry leaves it, and what derives from it, alone. */
    private val localRoster: Flow<Roster> = localEnvelope.filterNotNull().map { it.roster }.distinctUntilChanged()

    /** Whether this device knows it was removed, see [KeyguardSessionStatus.revoked]. */
    private val revokedFlow: Flow<Boolean> = statusSink.map { it.revoked }.distinctUntilChanged()

    /**
     * The active devices of the roster but this one, or `null` once this device was removed:
     * a device that learned it from a peer syncs with no one, even before its roster says so.
     */
    private val activePeersFlow: Flow<Map<String, RosterDevice>?> = combine(
        localRoster,
        revokedFlow,
    ) { roster, revoked ->
        if (revoked) null else activePeers(roster)
    }.distinctUntilChanged()

    /** Syncs until cancelled; leaving closes every session and the relay connection. */
    suspend fun run(): Nothing = streams.closingLeft {
        refreshLocalEnvelope()
        runRelay()
    }

    private suspend fun runRelay(): Nothing {
        // A device that knows it was removed has no one to sync with.
        if (localEnvelope.value?.let { activePeers(it.roster) } == null) {
            updatePeers(null)
            awaitCancellation()
        }
        val rooms = try {
            pairRooms()
        } catch (e: IllegalArgumentException) {
            // Broken settings do not fix themselves: wait for new ones.
            statusSink.update { it.copy(relay = DKeyguardRelayStatus.State.FAILED, lastError = e.message) }
            awaitCancellation()
        }
        coroutineScope {
            launch {
                mailbox.run(rooms.objects, rooms.endpoint) { storage ->
                    statusSink.update { it.copy(storage = storage) }
                }
            }
            launch { watchRelay() }
            launch { whileLive() }
            supervisePeers(rooms)
        }
    }

    /**
     * Rebuilds the local state and publishes it to the captured sessions. Roster writes
     * are waited for even when the envelope is unchanged or a revocation ends a session.
     */
    suspend fun publish(timeoutMs: Long): Boolean = publication().invoke(timeoutMs)

    /** Captures the sessions before an operation changes the roster and they start closing. */
    internal fun publication(): suspend (Long) -> Boolean {
        val sessions = running.value.filter { it.wasEstablished }
        return { timeoutMs ->
            withTimeoutOrNull(timeoutMs) {
                val envelope = refreshLocalEnvelope()
                sessions.map { session -> async { session.publish(envelope) } }.awaitAll().all { it }
            } == true
        }
    }

    /**
     * Waits until the relay connection either fails or is up and every peer that shows
     * up within [presenceGraceMs] holds the local state and handed over its own, or an
     * envelope too large stands in the way. Meanwhile the relay storage of each peer does
     * its first round, within [KeyguardMailboxTimings.settleMs]: the peers that are offline
     * get the local state, and this device theirs, see [KeyguardMailbox].
     */
    suspend fun awaitSettled(presenceGraceMs: Long = PRESENCE_GRACE_MS) {
        val local = localEnvelope.filterNotNull().first()
        val peers = activePeers(local.roster)
        if (peers.isNullOrEmpty() && revokedPeers.of(local.roster).isEmpty()) return
        // A removed device has no one to settle with.
        val joined = status.first {
            it.revoked || it.relay !in setOf(DKeyguardRelayStatus.State.CONNECTING, DKeyguardRelayStatus.State.OFFLINE)
        }
        if (joined.revoked || joined.relay != DKeyguardRelayStatus.State.CONNECTED) return
        mailbox.settling(peers.orEmpty().keys) {
            // Removed peers may be present too, while they are told. A one-shot exchange
            // must not close their rooms before the notice has been written.
            val present = withTimeoutOrNull(presenceGraceMs) {
                presentPeers.first { it.isNotEmpty() }
            }
            if (present != null) {
                combine(presentPeers, progress, status, revokedPeers.settled) { peers, sessions, status, told ->
                    status.revoked || peers.all { id ->
                        if (id in status.peers) sessions[id]?.exchangeDone == true else id in told
                    }
                }.first { it }
            }
        }
    }

    /**
     * Asks the running sessions with the [peers], or with every peer if `null`, to request
     * the roster and envelope of their peer again, within the budget of each session.
     */
    suspend fun requestResend(peers: Set<String>? = null) {
        running.value
            .filter { peers == null || it.peerId in peers }
            .forEach { it.requestResend() }
        mailbox.requestResend(peers)
    }

    /**
     * Ends every running session, telling each peer, and waits until the last frames left;
     * no connection is made or taken after it, see [connectUnlessClosing]. The registry
     * calls it before the session ends: otherwise a peer keeps the session until it times
     * out.
     */
    suspend fun closeSessions() {
        closing.value = true
        val sessions = running.value
        sessions.forEach { it.close() }
        running.first { current -> sessions.none { it in current } }
    }

    private suspend fun pairRooms(): PairRooms {
        val groupKey = P2pEncoding.base64UrlOrNull(membership.groupKeyBase64)
        require(groupKey != null && groupKey.size == P2P_GROUP_KEY_SIZE) {
            "The group key of the sync group is malformed."
        }
        val noisePrivate = membership.device.noisePrivateKey()
        val noisePublic = P2pEncoding.base64UrlOrNull(membership.device.noisePublicKeyBase64)
        val kemPrivate = P2pEncoding.base64UrlOrNull(membership.device.kemPrivateKeyBase64)
        require(noisePublic != null && kemPrivate != null) {
            "The keys of this device are malformed."
        }
        return PairRooms(
            endpoint = KeyguardRelayDefaults.endpoint(membership.network),
            groupKey = groupKey,
            scope = store.scope(groupId),
            noisePrivate = noisePrivate,
            noisePublic = noisePublic,
            kemPrivate = kemPrivate,
        )
    }

    /** The relay rooms of the pairs of this device and its peers, see [RelayRoom.forPair]. */
    private inner class PairRooms(
        val endpoint: RelayEndpoint,
        private val groupKey: ByteArray,
        private val scope: SyncScope,
        private val noisePrivate: ByteArray,
        private val noisePublic: ByteArray,
        private val kemPrivate: ByteArray,
    ) {
        /**
         * The keys of the objects the devices of the group share on the relay, under the
         * object key of the local state, see [ObjectKey]; `null` while it holds none that
         * is usable, see [ObjectKey.usable]. So a key that leaves a removed device uncovered
         * makes no request until a new key comes.
         */
        val objects: Flow<GroupObjects?> = localEnvelope
            .filterNotNull()
            .map { local -> local.envelope.entries.firstOrNull { it.key == ObjectKey.ENTRY_KEY } to local.roster }
            .distinctUntilChanged()
            .map { (entry, roster) ->
                ObjectKey.usable(entry, RosterActivity(validated(roster), crypto::sha256))
            }
            .distinctUntilChanged { old, new -> old?.key.contentEquals(new?.key) }
            .map { key -> key?.let { dependencies.roomConnector.groupObjects(it, groupKey, scope) } }

        /**
         * Joins the room of the pair with [peer] and runs [block] in it, with the keys of
         * the items the two devices leave on the relay, reporting the relay state and the
         * presence of the peer meanwhile. Returns at once if the keys of the peer are
         * malformed.
         */
        suspend fun join(
            peer: RosterDevice,
            allowLocalNetwork: Boolean = membership.network.allowLan,
            block: suspend CoroutineScope.(room: KeyguardRoom, mailbox: PairMailbox?) -> Unit,
        ) {
            val room = P2pEncoding.base64UrlOrNull(peer.noisePub)
                ?.let { peerNoise ->
                    val peerKem = peer.keys.mlKem1024?.let(P2pEncoding::base64UrlOrNull)
                    runCatchingUnlessCancelled {
                        dependencies.roomConnector
                            .pairRoom(groupKey, scope, noisePrivate, noisePublic, peerNoise, kemPrivate, peerKem)
                    }.getOrNull()
                }
                ?: return
            val spec = KeyguardRoomSpec(
                endpoint = endpoint,
                room = room,
                localDeviceId = membership.device.deviceId,
                allowLocalNetwork = allowLocalNetwork,
            )
            try {
                dependencies.roomConnector.join(
                    spec = spec,
                    onSelectedPath = { peerId, path -> paths.update { it + (peerId to path) } },
                ) { joined ->
                    // The watchers stop with the block, so the room is left once it returns.
                    val watchers = launch {
                        launch {
                            joined.relay.state.collect { state -> roomStates.update { it + (peer.id to state) } }
                        }
                        joined.signaling.presence.collect { present ->
                            presentPeers.update { if (peer.id in present) it + peer.id else it - peer.id }
                        }
                    }
                    try {
                        block(joined, room.mailbox)
                    } finally {
                        watchers.cancel()
                    }
                }
            } finally {
                roomStates.update { it - peer.id }
                presentPeers.update { it - peer.id }
            }
        }
    }

    private suspend fun refreshLocalEnvelope(): LocalEnvelope = refreshLock.withLock {
        // Hashed and encoded once here, not by each session.
        val fresh = store.localEnvelope(groupId, membership, crypto::sha256)
        // A publication and a change of the rows may both refresh it, one at a time, so an
        // older read never replaces a newer one. An unchanged one stays as it is, so the
        // sessions see no change and keep what they made of it.
        localEnvelope.value
            ?.takeIf { it.contentHash == fresh.contentHash && it.roster == fresh.roster }
            ?: fresh.also { localEnvelope.value = it }
    }

    /**
     * Mirrors the relay state of the rooms into the status: connected once any room is
     * joined, failed if the last attempt of every room failed, and incompatible if the
     * relay is one this build can not use. With no other device there is no room to join,
     * and nothing to reach.
     */
    private suspend fun watchRelay() {
        roomStates.collect { states ->
            val errors = states.values
                .map { (it as? RelayState.Disconnected)?.error }
                .takeIf { errors -> errors.isNotEmpty() && errors.all { it != null } }
                .orEmpty()
            val error = errors.firstOrNull { it == RelayError.Incompatible } ?: errors.firstNotNullOfOrNull { it }
            statusSink.update { status ->
                when {
                    states.isEmpty() -> status.copy(relay = DKeyguardRelayStatus.State.OFFLINE)

                    states.values.any { it is RelayState.Joined } ->
                        status.copy(relay = DKeyguardRelayStatus.State.CONNECTED)

                    error == RelayError.Incompatible ->
                        status.copy(relay = DKeyguardRelayStatus.State.INCOMPATIBLE, lastError = "relay_$error")

                    error != null -> status.copy(relay = DKeyguardRelayStatus.State.FAILED, lastError = "relay_$error")
                    else -> status.copy(relay = DKeyguardRelayStatus.State.CONNECTING)
                }
            }
        }
    }

    /** A held session merges what arrives, and hands local edits over, through sync passes. */
    private suspend fun whileLive() {
        live.collectLatest { isLive ->
            if (isLive) {
                coroutineScope {
                    launch { requestSyncs() }
                    watchLocalChanges()
                }
            }
        }
    }

    private suspend fun requestSyncs() {
        val id = P2pGroupId(groupId)
        for (request in syncRequests) {
            attempt { dependencies.queueSync().invoke(id).bind() }
        }
    }

    /** A change of the local rows refreshes the envelope. */
    @OptIn(FlowPreview::class)
    private suspend fun watchLocalChanges() {
        store
            .changes(groupId)
            .debounce(CHANGE_DEBOUNCE_MS)
            .collect {
                attempt { refreshLocalEnvelope() }
            }
    }

    /**
     * Keeps one room per active peer of the roster, and per removed peer to tell, see
     * [servePeer]; never returns.
     */
    private suspend fun CoroutineScope.supervisePeers(rooms: PairRooms): Nothing {
        val tellable = combine(
            localRoster,
            revokedFlow,
            revokedPeers.settled,
        ) { roster, revoked, _ ->
            // A removed device has no one to tell.
            if (revoked) emptyMap() else revokedPeers.of(roster)
        }.stateIn(this)
        val peers = mutableMapOf<String, Job>()
        combine(activePeersFlow, tellable) { active, tell -> active to tell }
            .collect { (active, tell) ->
                // A removed peer gets no new session while its dialer winds down.
                dialedIds.value = active.orEmpty().keys
                // Before the dialers start, so none of their updates is lost.
                updatePeers(active)
                (active.orEmpty() + tell).values
                    .filter { peers[it.id]?.isActive != true }
                    .forEach { peer -> peers[peer.id] = launch { servePeer(rooms, peer, tellable) } }
            }
        awaitCancellation()
    }

    /**
     * Dials [peer] in the room of the pair for as long as it is active. Once it is removed,
     * the session that runs tells it before it goes; a peer that was not told then is told
     * by [KeyguardRevokedPeers] while it is in [tellable], in the room again but without
     * direct connections. Returns once there is nothing left to do.
     */
    private suspend fun servePeer(
        rooms: PairRooms,
        peer: RosterDevice,
        tellable: StateFlow<Map<String, RosterDevice>>,
    ) {
        if (peer.id in dialedIds.value) {
            rooms.join(peer) { room, keys ->
                val removed = dialedIds.map { peer.id !in it }
                val storage = mailbox.launchIn(this, peer, room.relay, keys, rooms.endpoint, removed)
                val dialer = launch { dial(room, peer) }
                dialedIds.first { peer.id !in it }
                // Let the session finish writing its roster and BYE before cancelling
                // its dialer. The timeout bounds a stalled or failed transport.
                val sessions = running.value.filter { it.peerId == peer.id }.toSet()
                withTimeoutOrNull(REVOKED_GRACE_MS) {
                    running.first { current -> sessions.none { it in current } }
                }
                dialer.cancelAndJoin()
                // What this device left on the relay for the removed peer goes too.
                mailbox.awaitRetired(storage)
            }
        }
        if (peer.id in tellable.value) {
            rooms.join(peer, allowLocalNetwork = false) { room, _ -> revokedPeers.notify(room, peer) }
        }
    }

    /** Lists the active [peers] in the status, or marks this device revoked if `null`. */
    private fun updatePeers(peers: Map<String, RosterDevice>?) {
        val active = peers.orEmpty()
        statusSink.update { status ->
            status.copy(
                revoked = peers == null,
                peers = active.keys.associateWith { status.peers[it] ?: DKeyguardPeerStatus.Connection.Offline },
            )
        }
    }

    /**
     * Returns the valid records of [roster], with a validator of its own for each call: it
     * remembers what it checked, and is not thread-safe.
     */
    private fun validated(roster: Roster): Roster = RosterValidator(crypto, membership.groupId).validate(roster)

    /** The active devices of [roster] but this one, or `null` if this device is not active. */
    private fun activePeers(roster: Roster): Map<String, RosterDevice>? {
        val valid = validated(roster)
        val self = membership.device.deviceId
        return if (RosterActivity(valid, crypto::sha256).isActive(self)) {
            valid.activeDevices(crypto::sha256)
                .filter { it.id != self }
                .associateBy { it.id }
        } else {
            null
        }
    }

    /** Connects to [peer] whenever it is in the room, for as long as it is active. */
    private suspend fun dial(room: KeyguardRoom, peer: RosterDevice) {
        val initiator = membership.device.deviceId < peer.id
        val backoff = ReconnectBackoff(maxDelayMs = INITIATOR_MAX_RETRY_MS)
        // The connection the peer opened while the last session ran, which replaces it.
        var replacement: AcceptedStream? = null
        while (true) {
            val accepted = replacement
            if (accepted == null) awaitDialable(room, peer)
            // Taken before the session connects: a peer that comes back meanwhile is told again.
            val visit = room.signaling.relayPeerOf(peer.id)
            val end = attempt { session(room, peer, initiator, accepted) }
            replacement = end?.replacement
            if (replacement != null && !closing.value) continue
            replacement?.stream?.close()
            // A device that ended the session to connect again does so at once.
            val reconnect = end?.established == true && end.reason == CloseReason.RECONNECT
            setPeer(
                peer.id,
                if (reconnect) DKeyguardPeerStatus.Connection.Connecting else DKeyguardPeerStatus.Connection.Offline,
            )
            onSessionEnd(peer, end, visit)
            if (end?.established == true) backoff.reset()
            if (!reconnect) delay(if (initiator) backoff.nextDelayMs() else RESPONDER_RETRY_MS)
        }
    }

    /**
     * Suspends until [peer] is in the room and still active; a removed peer gets no new
     * session. The peer shows offline while it waits.
     */
    private suspend fun awaitDialable(room: KeyguardRoom, peer: RosterDevice) {
        val dialable = combine(room.signaling.presence, dialedIds) { present, active ->
            peer.id in present && peer.id in active
        }
        if (!dialable.first()) {
            setPeer(peer.id, DKeyguardPeerStatus.Connection.Offline)
            dialable.first { it }
        }
        setPeer(peer.id, DKeyguardPeerStatus.Connection.Connecting)
    }

    /**
     * Records how the session with [peer], in its [visit] of the room, ended; suspends for
     * good if no session may follow.
     */
    private suspend fun onSessionEnd(peer: RosterDevice, end: SessionEnd?, visit: String?) {
        // The removed peer was told, unless it was gone already: then it is told once it
        // comes back, under a new relay id, see servePeer.
        if (end?.toldRevoked == true && visit != null) {
            revokedPeers.toldVisits.update { it + (peer.id to visit) }
        }
        when (end?.reason) {
            // The roster of the session shows that this device was removed: the next sync
            // pass records it. A peer's word alone never ends here, see SyncSessionStateMachine.
            CloseReason.LOCAL_REVOKED -> {
                statusSink.update { it.copy(revoked = true) }
                awaitCancellation()
            }
            // The roster of the session shows that the peer was removed: the roster of this
            // device drops the dialer soon.
            CloseReason.PEER_REVOKED -> if (peer.id !in dialedIds.value) awaitCancellation()
            else -> Unit
        }
        if (closing.value) awaitCancellation()
    }

    /**
     * How a session ended: whether it got [established], the [reason], if any, and whether
     * its BYE [went out][byeSent]. A session that a newer connection of the peer took over
     * hands that over as [replacement].
     */
    private class SessionEnd(
        val established: Boolean,
        val reason: CloseReason?,
        val byeSent: Boolean = false,
        val replacement: AcceptedStream? = null,
    ) {
        /** The established session told the peer it was removed: the roster and BYE went out. */
        val toldRevoked: Boolean get() = established && reason == CloseReason.PEER_REVOKED && byeSent
    }

    /**
     * An authenticated stream of [kind], and the ICE servers this device gave its
     * connection, if any.
     */
    private class AcceptedStream(
        val kind: TransportKind,
        val stream: ByteStream,
        val iceServers: IceServersGrant? = null,
    )

    /**
     * Runs one session with [peer] until it ends: over [accepted], or else over a new
     * connection, unless the sessions close for good. A responder takes a connection the
     * peer opens meanwhile instead.
     */
    private suspend fun session(
        room: KeyguardRoom,
        peer: RosterDevice,
        initiator: Boolean,
        accepted: AcceptedStream?,
    ): SessionEnd {
        val connection = accepted ?: connectUnlessClosing {
            val role = if (initiator) ConnectRole.INITIATOR else ConnectRole.RESPONDER
            val selected = room.connect(peer.id, role)
            AcceptedStream(selected.kind, handshake(selected.stream, peer, initiator), selected.iceServers)
        }
        return when {
            connection == null -> SessionEnd(established = false, reason = null)
            // The device with the smaller id dials; only its peer sees it dial again.
            initiator -> runSession(peer, connection)
            else -> respondedSession(room, peer, connection)
        }
    }

    /**
     * Runs the session of a responder with [peer] over [connection] until it ends, or until
     * the peer opens a newer connection, which replaces it.
     */
    private suspend fun respondedSession(
        room: KeyguardRoom,
        peer: RosterDevice,
        connection: AcceptedStream,
    ): SessionEnd = coroutineScope {
        val current = async { runSession(peer, connection) }
        val newer = async { acceptAgain(room, peer) }
        var adopted = false
        try {
            select<SessionEnd> {
                current.onAwait { end -> end }
                newer.onAwait { stream ->
                    current.cancelAndJoin()
                    adopted = true
                    SessionEnd(established = true, reason = null, replacement = stream)
                }
            }
        } finally {
            // A connection of the peer that no session takes holds a native Noise session
            // until it is closed: also one accepted just as the current session ended.
            if (!adopted) withContext(NonCancellable) { closeAccepted(newer) }
        }
    }

    /** Stops [accepting], and closes the stream accepted already, if any. */
    private suspend fun closeAccepted(accepting: Deferred<AcceptedStream?>) {
        accepting.cancelAndJoin()
        if (!accepting.isCancelled) accepting.await()?.stream?.close()
    }

    /**
     * Waits until [peer] opens another connection to this device, as the responder, and
     * returns it once the peer confirmed its keys, see [confirmed]. The peer dials only
     * when it holds no session with this device: the session that runs is stale then.
     */
    private suspend fun acceptAgain(room: KeyguardRoom, peer: RosterDevice): AcceptedStream {
        while (true) {
            room.signaling.presence.first { peer.id in it }
            // The peer may well not dial.
            val accepted = runCatchingUnlessCancelled {
                connectUnlessClosing {
                    val selected = room.connect(peer.id, ConnectRole.RESPONDER)
                    confirmed(selected, handshake(selected.stream, peer, initiator = false))
                }
            }.getOrNull()
            if (accepted != null) return accepted
            // A session that ends for good takes no newer connection: it would close it unread.
            if (closing.value) awaitCancellation()
            delay(RESPONDER_RETRY_MS)
        }
    }

    /**
     * Returns the authenticated connection that [connect] makes or takes, or `null` once the
     * sessions close for good, see [closeSessions]: a peer would count what it sent on a
     * connection taken then as delivered, though no session reads it. Stops [connect] then,
     * and closes a connection it completed just as the sessions closed.
     */
    private suspend fun connectUnlessClosing(
        connect: suspend () -> AcceptedStream?,
    ): AcceptedStream? = coroutineScope {
        val connection = async { connect() }
        val closed = async { closing.first { it } }
        val connected = select<Boolean> {
            connection.onAwait { true }
            closed.onAwait { false }
        }
        closed.cancel()
        if (connected) {
            connection.await()
        } else {
            closeAccepted(connection)
            null
        }
    }

    /**
     * Returns the authenticated [stream] of the [selected] connection once its first
     * message, the HELLO of the peer, arrived within [REPLACEMENT_HELLO_TIMEOUT_MS], with
     * that message to read again; else closes it and returns `null`. Noise KKhfs confirms the
     * keys of the responder only with the first message of the initiator: a replayed
     * handshake message completes the handshake, but never brings a message that decrypts.
     */
    private suspend fun confirmed(selected: SelectedStream, stream: ByteStream): AcceptedStream? {
        var first: ByteArray? = null
        try {
            first = withTimeoutOrNull(REPLACEMENT_HELLO_TIMEOUT_MS) { stream.read() }
        } finally {
            if (first == null) withContext(NonCancellable) { stream.close() }
        }
        return first?.let { AcceptedStream(selected.kind, PrefetchedStream(stream, it), selected.iceServers) }
    }

    /** Runs one session with [peer] over the authenticated [connection] until it ends. */
    private suspend fun runSession(
        peer: RosterDevice,
        connection: AcceptedStream,
    ): SessionEnd {
        val envelope = localEnvelope.value ?: refreshLocalEnvelope()
        val blobs = dependencies.store.blobs(groupId)
        val machine = SyncSessionStateMachine(
            config = SyncSessionConfig(
                localDeviceId = membership.device.deviceId,
                peerDeviceId = peer.id,
                peerNoisePub = peer.noisePub,
                groupId = membership.groupId,
                timings = dependencies.timings,
                blobs = blobs?.config ?: BlobExchangeConfig(),
                maxEnvelopeSize = dependencies.maxEnvelopeSize,
            ),
            roster = validated(envelope.roster),
            crypto = crypto,
            compressor = dependencies.compressor,
        )
        val session = KeyguardPeerSession(
            peerId = peer.id,
            stream = connection.stream,
            machine = machine,
            listener = PeerListener(peer.id),
            clock = clock,
            blobs = blobs?.let {
                KeyguardPeerBlobs(
                    peerId = peer.id,
                    blobs = it,
                    changes = dependencies.store.changes(groupId),
                )
            },
        )
        running.update { it + session }
        val reason = try {
            // The sessions may be closing already.
            if (closing.value) session.close()
            runWatched(session, peer.id, connection, envelope)
        } finally {
            running.update { it - session }
            progress.update { it - peer.id }
            updateTooLarge()
        }
        return SessionEnd(
            established = session.wasEstablished,
            reason = reason,
            byeSent = session.byeSent,
        )
    }

    /**
     * Runs [session], over the [connection] with [peerId], from the local [envelope] until
     * it ends. Meanwhile it mirrors the progress of the session, and moves it to a new
     * connection before TURN drops this one, see [reconnectBeforeTurnExpires].
     */
    private suspend fun runWatched(
        session: KeyguardPeerSession,
        peerId: String,
        connection: AcceptedStream,
        envelope: LocalEnvelope,
    ): CloseReason? = coroutineScope {
        val watcher = launch { watch(session, peerId, connection.kind) }
        val refresher = launch {
            reconnectBeforeTurnExpires(
                kind = connection.kind,
                iceServers = connection.iceServers,
                overTurn = paths.map { it[peerId] == TransportPath.RELAY },
            ) {
                session.close(CloseReason.RECONNECT)
            }
        }
        session.run(envelope, localEnvelope.filterNotNull())
            .also {
                watcher.cancel()
                refresher.cancel()
            }
    }

    /** Mirrors the progress of [session] into the status and the peer state, until cancelled. */
    private suspend fun watch(
        session: KeyguardPeerSession,
        peerId: String,
        kind: TransportKind,
    ) {
        var recorded: KeyguardPeerProgress? = null
        session.progress.collect { p ->
            val transport = keyguardTransportOf(kind, paths.value[peerId])
            onProgress(peerId, p, transport)
            if (p.established && p != recorded) {
                recorded = p
                recordPeer(peerId, transport)
            }
        }
    }

    /**
     * Authenticates [stream] as [peer]'s. The stream is tracked until it is closed, see
     * [streams]: a race may be cancelled before any session takes it.
     */
    private suspend fun handshake(
        stream: ByteStream,
        peer: RosterDevice,
        initiator: Boolean,
    ): ByteStream {
        val remoteStatic = P2pEncoding.base64UrlOrNull(peer.noisePub)
        val localPrivate = P2pEncoding.base64UrlOrNull(membership.device.noisePrivateKeyBase64)
        if (remoteStatic == null || localPrivate == null) {
            stream.close()
            throw TransportException("The Noise keys of the devices are malformed")
        }
        val noise = NoiseByteStream.handshake(
            stream = stream,
            suite = NoiseSuite.KK_HFS,
            initiator = initiator,
            localPrivateKey = localPrivate,
            remotePublicKey = remoteStatic,
        )
        // KKhfs pins the key already; a mismatch here is a bug, never a session.
        if (!noise.remoteStatic.contentEquals(remoteStatic)) {
            noise.close()
            throw TransportException("The device is not the one of the roster")
        }
        return streams.track(noise)
    }

    private fun onProgress(
        peerId: String,
        p: KeyguardPeerProgress,
        transport: DKeyguardTransport,
    ) {
        progress.update { it + (peerId to p) }
        val status = if (p.established) {
            DKeyguardPeerStatus.Connection.Connected(
                transport = transport,
                // The devices are not in sync while the state of the peer is on its way.
                upToDate = p.upToDate && !p.receiving,
                tooLarge = p.peerTooLarge,
            )
        } else {
            DKeyguardPeerStatus.Connection.Connecting
        }
        setPeer(peerId, status)
        updateTooLarge()
    }

    /** Mirrors whether a running session finds the local state too large to send. */
    private fun updateTooLarge() {
        val size = progress.value.values.firstNotNullOfOrNull { it.localTooLarge }
        val tooLarge = size?.let { DKeyguardSyncTooLarge(size = it, limit = dependencies.maxEnvelopeSize) }
        statusSink.update { status -> if (status.tooLarge == tooLarge) status else status.copy(tooLarge = tooLarge) }
    }

    private suspend fun recordPeer(
        peerId: String,
        transport: DKeyguardTransport,
    ) {
        val record = KeyguardPeerRecord(
            deviceId = peerId,
            lastSeenAt = dependencies.now(),
            transport = transport,
        )
        // Best effort: the group may be on its way out.
        attempt {
            withContext(NonCancellable) {
                store.updatePeer(groupId, record)
            }
        }
    }

    private fun setPeer(peerId: String, peerStatus: DKeyguardPeerStatus.Connection) {
        statusSink.update { status ->
            if (peerId in status.peers) status.copy(peers = status.peers + (peerId to peerStatus)) else status
        }
    }

    private fun onReceived() {
        if (live.value) syncRequests.trySend(Unit)
    }

    /** Runs [block]; a failure is recorded as the last error and yields `null`. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = runCatchingUnlessCancelled {
        block()
    }.getOrElse { e ->
        statusSink.update { it.copy(lastError = e.message ?: e::class.simpleName) }
        null
    }

    private inner class PeerListener(
        private val peerId: String,
    ) : KeyguardPeerListener {
        override fun onEnvelope(envelope: Envelope, hash: String) {
            // The same state again needs no pass of its own.
            if (dependencies.inbox.post(groupId, peerId, envelope, hash)) onReceived()
        }

        override fun onRoster(roster: Roster) {
            dependencies.inbox.postRoster(groupId, peerId, roster)
            onReceived()
        }
    }
}
