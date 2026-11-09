package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxPolicy
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxState
import com.artemchep.keyguard.feature.p2psync.model.DeviceFeature
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.activeDevices
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardInbox
import com.artemchep.keyguard.provider.keyguard.transport.GroupObjects
import com.artemchep.keyguard.provider.keyguard.transport.PairMailbox
import com.artemchep.keyguard.provider.keyguard.transport.RelayClient
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxLimits
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import com.artemchep.keyguard.provider.keyguard.transport.RelayState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * How often relay storage asks for and posts the state of a peer, see [KeyguardMailbox].
 *
 * @property fetchIntervalMs a peer with no live session is asked for its state this often.
 * @property postDebounceMs local changes are coalesced for this long before they are posted.
 * @property recheckMs how often an idle peer looks for a blob to pull again.
 * @property maxBackoffMs the longest pause after the network failed.
 * @property settleMs how long a one-shot exchange waits for the first round, see
 *   [KeyguardMailbox.awaitFirstRound].
 * @property relayRecheckMs how long what the relay holds of this device counts as known:
 *   the relay may trim an item at any time, so it is asked again after that.
 * @property refreshAheadPercent the state of this device is posted again, the same, once
 *   less than this share of its lifetime is left, see
 *   [com.artemchep.keyguard.feature.p2psync.mailbox.MailboxPolicy.isExpiring].
 */
data class KeyguardMailboxTimings(
    val fetchIntervalMs: Long = FETCH_INTERVAL_MS,
    val postDebounceMs: Long = POST_DEBOUNCE_MS,
    val recheckMs: Long = RECHECK_MS,
    val maxBackoffMs: Long = MAX_BACKOFF_MS,
    val settleMs: Long = SETTLE_MS,
    val relayRecheckMs: Long = RELAY_RECHECK_MS,
    val refreshAheadPercent: Long = REFRESH_AHEAD_PERCENT,
) {
    companion object {
        /** 5 minutes. */
        const val FETCH_INTERVAL_MS: Long = 5L * 60L * 1_000L

        /** 10 seconds. */
        const val POST_DEBOUNCE_MS: Long = 10_000L

        /** A minute. */
        const val RECHECK_MS: Long = 60_000L

        /** 15 minutes. */
        const val MAX_BACKOFF_MS: Long = 15L * 60L * 1_000L

        /** 20 seconds, within the deadline of a one-shot exchange. */
        const val SETTLE_MS: Long = 20_000L

        /** 30 minutes. */
        const val RELAY_RECHECK_MS: Long = 30L * 60L * 1_000L

        /** A quarter of the lifetime of an item. */
        const val REFRESH_AHEAD_PERCENT: Long = 25L
    }
}

/** Converts the Unix seconds and lifetimes the relay names to milliseconds. */
internal const val MILLIS_PER_SECOND: Long = 1_000L

/**
 * One joined connection to the relay that keeps items: its [ticket], and the [limits] of the
 * relay. The workers of the peers and of the objects share it, see [KeyguardMailbox].
 */
internal class KeyguardMailboxJoin(
    val ticket: String,
    val limits: RelayMailboxLimits,
) {
    /** How long an item this device leaves lives, see [MailboxPolicy.ttlSeconds]. */
    val ttlSeconds: Long get() = MailboxPolicy.ttlSeconds(limits.ttlSeconds)

    /** When an item left at [now] stops counting, in epoch milliseconds. */
    fun expiresAtMs(now: Instant): Long = now.toEpochMilliseconds() + ttlSeconds * MILLIS_PER_SECOND
}

/** `true` if the relay holds no such item any longer: a DELETE removed it, or there was none. */
internal val RelayMailboxResult.isGone: Boolean
    get() = this is RelayMailboxResult.Done || this == RelayMailboxResult.NotFound

/**
 * Relay storage of one sync group, for its [KeyguardSessionManager]: the relay keeps the
 * state of this device for each peer that is offline, and the state the peer left for
 * this device, see [PairMailbox]. Each peer is served by a [KeyguardPeerMailbox] while its
 * room is joined. The blobs are the group's: the relay keeps one object per blob for
 * every device, under the object key of the group, served by the [objects] while any room
 * is joined, see [GroupObjects].
 *
 * Every device holds the same records, so the group stores them only while every active
 * device allows it in its signed record, see [DeviceFeature.RELAY_STORAGE]. Until then a
 * device that allows it leaves its roster only, with no entries and no blobs, so the others
 * learn that it did without a live session. Asking a peer for its state stores nothing, so it needs
 * only this device's own choice: a peer with relay storage off never fetches the roster.
 *
 * What arrives goes to the inbox of the group, as from a session, and the next sync pass
 * merges it. A refusal of the relay that names a time pauses every peer of the group, as
 * they share one relay.
 *
 * A device with relay storage off makes no request to it, unless the relay may still hold
 * what it left there, see [KeyguardRelayStorageUse]: then it removes that once, see
 * [isRetiring].
 *
 * @param clock a monotonic clock in milliseconds.
 */
// The shared state of the peers of one group.
@Suppress("LongParameterList", "TooManyFunctions")
internal class KeyguardMailbox(
    val groupId: String,
    private val membership: P2pMembership,
    private val dependencies: KeyguardSessionDependencies,
    /** The latest local state, once the session built it. */
    val localEnvelope: StateFlow<LocalEnvelope?>,
    /** The progress of each running session, by peer. */
    val progress: StateFlow<Map<String, KeyguardPeerProgress>>,
    /** Queues a sync pass of what arrived, if the session is live. */
    private val onReceived: () -> Unit,
    val clock: () -> Long,
) {
    companion object {
        /** How long a peer that was removed gets for its items to be deleted. */
        const val RETIRE_TIMEOUT_MS: Long = 10_000L
    }

    /**
     * The validated local [roster], with its [activeIds], whether this device lists relay
     * storage in its record, [selfListed], whether every active device does, [ready], and
     * the labels of the other ones that do not, [waitingFor].
     */
    class RosterGate(
        val roster: Roster,
        val activeIds: Set<String>,
        val selfListed: Boolean,
        val ready: Boolean,
        val waitingFor: List<String>,
    )

    /** The [activeIds] of a local [roster], as it was before validation. */
    private class LocalActivity(
        val roster: Roster,
        val activeIds: Set<String>,
    )

    /** A pause of every request until [untilMs] by the [clock], [until] by the wall clock. */
    private data class Pause(
        val untilMs: Long,
        val until: Instant,
    )

    /** `true` if the user of this device allows relay storage. */
    val enabled: Boolean = membership.network.relayStorage

    /** The id of this device. */
    val selfId: String get() = membership.device.deviceId

    val timings: KeyguardMailboxTimings get() = dependencies.mailboxTimings

    val crypto get() = dependencies.crypto

    val maxEnvelopeSize: Int get() = dependencies.maxEnvelopeSize

    val objectDownloads: KeyguardObjectDownloads get() = dependencies.objectDownloads

    /** A validator of its own for each call: it remembers what it checked, and is not thread-safe. */
    private fun validator() = RosterValidator(crypto, membership.groupId)

    private val gateSink = MutableStateFlow<RosterGate?>(null)

    /**
     * Guards each read, merge and write of [gateSink]: the local roster and the peers'
     * deliveries are merged into the gate, so no update loses another, such as a peer
     * that turned relay storage off.
     */
    private val gateLock = SynchronizedObject()

    /** The gate of the latest local roster, once [run] computed it. */
    val gate: StateFlow<RosterGate?> = gateSink.asStateFlow()

    /** The activity of the last local roster [isActivePair] read, so each one is validated once. */
    @Volatile
    private var localActivity: LocalActivity? = null

    /** Whether the relay keeps items, as the room of each peer last joined it. */
    private val support = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val pauseSink = MutableStateFlow<Pause?>(null)

    /** The peers whose state the relay does not take: it is too large. */
    private val tooLarge = MutableStateFlow<Set<String>>(emptySet())

    /** The peers whose first round is over, see [awaitFirstRound]. */
    private val rounds = MutableStateFlow<Set<String>>(emptySet())

    /** Whether the objects did their first round, see [awaitFirstRound]. */
    private val objectsRound = MutableStateFlow(false)

    private val workers = MutableStateFlow<Map<String, KeyguardPeerMailbox>>(emptyMap())

    /** The objects of the blobs of the group. */
    val objects = KeyguardObjectMailbox(this)

    private val useMutex = Mutex()

    /** Until when the relay may hold what this device left there, once read, see [KeyguardRelayStorageUse]. */
    private var usedUntil: Long? = null

    private var usedUntilRead = false

    /** The peers whose items, and whether the objects, were removed once relay storage was turned off. */
    private val retiredPeers = MutableStateFlow<Set<String>>(emptySet())

    private val retiredObjects = MutableStateFlow(false)

    /** Now, by the wall clock. */
    fun now(): Instant = dependencies.now()

    /**
     * Keeps the gate of the local roster, ends the pauses once they are over, serves the
     * objects with the latest [keys] of the group at the relay [endpoint], and hands the
     * status to [onStatus] whenever it changes; never returns.
     *
     * @param keys the keys of the objects, or `null` while the group has no object key.
     */
    suspend fun run(
        keys: Flow<GroupObjects?>,
        endpoint: RelayEndpoint,
        onStatus: (DKeyguardRelayStorageStatus) -> Unit,
    ): Nothing = coroutineScope {
        launch {
            objects.run(
                keys = keys,
                relay = dependencies.relayMailboxes.get(endpoint),
                blobs = dependencies.store.blobs(groupId),
            )
        }
        launch {
            localEnvelope
                .filterNotNull()
                .map { it.roster }
                .distinctUntilChanged()
                .collect { roster ->
                    synchronized(gateLock) {
                        val validator = validator()
                        // The gate may know rosters the store has not merged yet.
                        val merged = gateSink.value?.roster?.let { validator.merge(it, roster) } ?: roster
                        gateSink.value = gateOf(merged, validator)
                    }
                }
        }
        launch {
            pauseSink.collectLatest { current ->
                current ?: return@collectLatest
                delay((current.untilMs - clock()).coerceAtLeast(0L))
                pauseSink.compareAndSet(current, null)
            }
        }
        combine(gateSink, support, pauseSink, tooLarge, ::statusOf)
            .distinctUntilChanged()
            .collect(onStatus)
        awaitCancellation()
    }

    private fun gateOf(roster: Roster, validator: RosterValidator): RosterGate {
        val validated = validator.validate(roster)
        val activity = RosterActivity(validated, crypto::sha256)
        val self = validated.devices.firstOrNull { it.id == selfId }
        val waiting = validated.devices
            .distinctBy { it.id }
            .filter { it.id in activity.activeIds && it.id != selfId }
            .filter { DeviceFeature.RELAY_STORAGE !in it.caps.features }
            .sortedBy { it.id }
        return RosterGate(
            roster = validated,
            activeIds = activity.activeIds,
            selfListed = self?.caps?.features?.contains(DeviceFeature.RELAY_STORAGE) == true,
            ready = DeviceFeature.RELAY_STORAGE in activity.caps.features,
            waitingFor = waiting.map { it.label },
        )
    }

    private fun statusOf(
        gate: RosterGate?,
        support: Map<String, Boolean>,
        pause: Pause?,
        tooLarge: Set<String>,
    ): DKeyguardRelayStorageStatus = when {
        !enabled -> DKeyguardRelayStorageStatus.Off
        // A relay that keeps nothing: every room says so.
        support.isNotEmpty() && support.values.none { it } -> DKeyguardRelayStorageStatus.Unsupported
        gate == null || !gate.ready -> DKeyguardRelayStorageStatus.Waiting(gate?.waitingFor.orEmpty())
        pause != null -> DKeyguardRelayStorageStatus.Paused(pause.until)
        tooLarge.isNotEmpty() -> DKeyguardRelayStorageStatus.TooLarge
        else -> DKeyguardRelayStorageStatus.Active
    }

    /** `true` if this device may store its state for its peers: every active device allows it. */
    fun isOpen(): Boolean = enabled && gateSink.value?.ready == true

    /**
     * `true` if this device and [peerId] are both active in the local [roster], and in the
     * roster of the gate, which may know of a removal first: a state item for a removed
     * peer could carry the object key the group wrote once it removed the peer.
     */
    fun isActivePair(roster: Roster, peerId: String): Boolean {
        val gate = gateSink.value
            ?: return false
        val activity = localActivity
            ?.takeIf { it.roster === roster }
            ?: LocalActivity(roster, RosterActivity(validator().validate(roster), crypto::sha256).activeIds)
                .also { localActivity = it }
        val pair = setOf(selfId, peerId)
        return activity.activeIds.containsAll(pair) && gate.activeIds.containsAll(pair)
    }

    /**
     * `true` if relay storage is off, but the relay may still hold what this device left
     * there while it was on: the state items of each peer and the objects are removed then,
     * once per session, see [markPeerRetired] and [markObjectsRetired]. Otherwise a device
     * with relay storage off makes no request to it.
     */
    suspend fun isRetiring(): Boolean {
        if (enabled) return false
        val until = useMutex.withLock {
            if (!usedUntilRead) {
                runCatchingUnlessCancelled { dependencies.relayStorageUse.usedUntil(groupId) }
                    .onSuccess {
                        usedUntil = it
                        usedUntilRead = true
                    }
            }
            usedUntil
        }
        return until != null && until > now().toEpochMilliseconds()
    }

    /** An item this device left over the connection of [join] is on the relay now, see [KeyguardRelayStorageUse]. */
    suspend fun recordUse(join: KeyguardMailboxJoin) {
        val until = join.expiresAtMs(now())
        runCatchingUnlessCancelled { dependencies.relayStorageUse.setUsedUntil(groupId, until) }
    }

    /**
     * The blobs this device needs not leave on its own, with the object key they are
     * settled under, or `null` before it first served the group, see
     * [KeyguardRelayStorageUse.settledBlobs].
     */
    suspend fun settledBlobs(): SettledBlobs? =
        runCatchingUnlessCancelled { dependencies.relayStorageUse.settledBlobs(groupId) }.getOrNull()

    /** Stores the blobs this device needs not leave on its own under the object key [keyId], see [settledBlobs]. */
    suspend fun setSettledBlobs(keyId: String, hashes: Set<String>) {
        runCatchingUnlessCancelled { dependencies.relayStorageUse.setSettledBlobs(groupId, keyId, hashes) }
    }

    /** What this device left for [peerId] is removed, see [isRetiring]. */
    suspend fun markPeerRetired(peerId: String) {
        retiredPeers.update { it + peerId }
        forgetUseOnceRetired()
    }

    /** The objects this device left are removed, see [isRetiring]. */
    suspend fun markObjectsRetired() {
        retiredObjects.value = true
        forgetUseOnceRetired()
    }

    /**
     * Forgets the use of relay storage once what this device left for every active peer,
     * and the objects, are removed: the relay holds nothing of it any longer.
     */
    private suspend fun forgetUseOnceRetired() {
        val roster = gateSink.value?.roster
            ?: return
        val peers = roster.activeDevices(crypto::sha256)
            .map { it.id }
            .filter { it != selfId }
        if (!retiredObjects.value || !retiredPeers.value.containsAll(peers)) return
        // The roster-only notices expire on their own.
        useMutex.withLock {
            runCatchingUnlessCancelled { dependencies.relayStorageUse.setUsedUntil(groupId, null) }
                .onSuccess { usedUntil = null }
        }
    }

    /**
     * Serves relay storage for [peer] over the [relay] connection of their room, with the
     * [keys] of the pair, at the relay [endpoint], until cancelled. Once [removed] says the
     * peer left the group, deletes what this device left for it and returns.
     */
    suspend fun serve(
        peer: RosterDevice,
        relay: RelayClient,
        keys: PairMailbox,
        endpoint: RelayEndpoint,
        removed: Flow<Boolean>,
    ) {
        val worker = KeyguardPeerMailbox(
            peer = peer,
            keys = keys,
            relay = dependencies.relayMailboxes.get(endpoint),
            mailbox = this,
        )
        workers.update { it + (peer.id to worker) }
        try {
            coroutineScope {
                val serving = launch { serveJoins(peer, relay, worker) }
                removed.first { it }
                serving.cancelAndJoin()
                // Nothing of it is needed any longer: it would wait out its time on the relay.
                if (enabled) worker.retire(relay)
            }
        } finally {
            workers.update { it - peer.id }
            support.update { it - peer.id }
            tooLarge.update { it - peer.id }
            objects.onPeerGone(peer.id)
        }
    }

    /** Runs the [worker] in each connection the room of [peer] joins, as long as it is joined. */
    private suspend fun serveJoins(
        peer: RosterDevice,
        relay: RelayClient,
        worker: KeyguardPeerMailbox,
    ) {
        relay.state.collectLatest { state ->
            val joined = state as? RelayState.Joined
                ?: return@collectLatest
            val limits = joined.limits.mailbox
            val ticket = joined.ticket
            support.update { it + (peer.id to (limits != null && ticket != null)) }
            if (limits == null || ticket == null) {
                // The relay keeps nothing: there is no round to wait for, of the objects either.
                markRound(peer.id)
                markObjectsRound()
                return@collectLatest
            }
            worker.run(KeyguardMailboxJoin(ticket, limits))
        }
    }

    /** Waits until each of the [peers] had its first round of relay storage, see [KeyguardPeerMailbox]. */
    suspend fun awaitFirstRound(peers: Set<String>) {
        rounds.first { it.containsAll(peers) }
        // With relay storage on, the blobs the relay holds for this device, or wants, too.
        if (enabled) objectsRound.first { it }
    }

    /**
     * Runs [block] while the relay storage of each of the [peers] does its first round, and
     * returns once both are done, or the round took [KeyguardMailboxTimings.settleMs]: a
     * one-shot exchange hands the local state to the peers that are offline, and takes
     * theirs, before it ends.
     */
    suspend fun settling(peers: Set<String>, block: suspend () -> Unit) = coroutineScope {
        val round = async { withTimeoutOrNull(timings.settleMs) { awaitFirstRound(peers) } }
        block()
        round.await()
    }

    /**
     * Serves [peer] in the [scope] of its room, see [serve], with the [keys] of the pair, if
     * any; returns the job, or `null` without keys.
     */
    // One argument per input of serve.
    @Suppress("LongParameterList")
    fun launchIn(
        scope: CoroutineScope,
        peer: RosterDevice,
        relay: RelayClient,
        keys: PairMailbox?,
        endpoint: RelayEndpoint,
        removed: Flow<Boolean>,
    ): Job? = keys?.let { scope.launch { serve(peer, relay, it, endpoint, removed) } }

    /**
     * Gives the [job] of a peer that was removed [RETIRE_TIMEOUT_MS] to delete what this
     * device left for it, see [serve], then ends it.
     */
    suspend fun awaitRetired(job: Job?) {
        if (job == null) return
        withTimeoutOrNull(RETIRE_TIMEOUT_MS) { job.join() }
        job.cancelAndJoin()
    }

    fun markRound(peerId: String) {
        rounds.update { it + peerId }
    }

    /** The objects did their first round, see [awaitFirstRound]. */
    fun markObjectsRound() {
        objectsRound.value = true
    }

    /** Asks the [peers], or every peer if `null`, for the state they left again, in full. */
    fun requestResend(peers: Set<String>?) {
        workers.value
            .filterKeys { peers == null || it in peers }
            .values
            .forEach { it.requestResend() }
    }

    /** Pauses every request to the relay for [durationMs]; a longer pause that runs stays. */
    fun pause(durationMs: Long) {
        val next = Pause(untilMs = clock() + durationMs, until = now() + durationMs.milliseconds)
        pauseSink.update { current -> if (current != null && current.untilMs >= next.untilMs) current else next }
    }

    /**
     * Suspends while the requests to the relay are paused. Only [run] ends a pause, so call
     * it while [run] runs, as the workers of the peers and of the objects do.
     */
    suspend fun awaitUnpaused() {
        pauseSink.first { it == null }
    }

    /** Marks whether the state for [peerId] is too large for the relay. */
    fun setTooLarge(peerId: String, value: Boolean) {
        tooLarge.update { if (value) it + peerId else it - peerId }
    }

    /**
     * Hands the [state] the peer [peerId] left to the inbox of the group: its roster, merged
     * into the validated local one so that no forged record waits there, then its envelope.
     * The gate follows the merged roster at once: a peer that turned relay storage off
     * closes it before this device posts again. A state whose roster merge is refused, see
     * [RosterValidator.mergeOrNull], is not delivered: its entries would be checked against
     * a roster without its revocations.
     *
     * A roster that equals the one of the gate is validated already, and one that also equals
     * the published local roster is merged already: it is not posted.
     */
    fun deliver(peerId: String, state: MailboxState) {
        val merged = synchronized(gateLock) {
            val validator = validator()
            val gate = gateSink.value?.roster
            val base = gate
                ?: localEnvelope.value?.roster?.let(validator::validate)
                ?: return
            when {
                gate != null && state.roster == gate -> gate.takeIf { it != localEnvelope.value?.roster }
                state.roster.founder == base.founder ->
                    validator.mergeOrNull(base, state.roster)
                        ?.also { merged -> if (merged != base) gateSink.value = gateOf(merged, validator) }
                        ?: return

                else -> null
            }
        }
        merged?.let { dependencies.inbox.postRoster(groupId, peerId, it) }
        // A slot of its own: a session may have handed over a newer one meanwhile.
        state.envelope?.let {
            dependencies.inbox.post(groupId, peerId, it.envelope, it.contentHash, KeyguardInbox.Source.RELAY)
        }
        onReceived()
    }
}
