package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.common.util.withRunForever
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the sessions of the sync groups this device is a member of. */
interface KeyguardSessionLauncher {
    /**
     * Holds the session of the group [groupId], of which this device has the [membership],
     * until cancelled: the session is live meanwhile, see [KeyguardSessionRegistry].
     */
    suspend fun hold(
        groupId: String,
        membership: P2pMembership,
    ): Nothing

    /**
     * Asks the running sessions of the group [groupId] with the [peers], or with every peer
     * if `null`, to request the roster and envelope of their peer again, within the budget
     * of each session, see
     * [com.artemchep.keyguard.feature.p2psync.session.SyncSessionStateMachine.requestPeerResend].
     * A session that starts later gets the whole state of its peer anyway.
     */
    suspend fun requestResend(groupId: String, peers: Set<String>? = null)
}

/**
 * Runs the sessions of the sync groups of the vault session: one session, and so one
 * connection of this device, per group. A session runs while someone leases it: the app
 * [holds][hold] it while in the foreground, and a sync pass leases it for its [exchange],
 * such as from Android's background sync. Once the last lease is gone, the session tells
 * the peers and disconnects.
 *
 * A held session is live: what arrives, and every local edit, queues a sync pass. A session
 * that only a pass leases queues none; the pass merges what arrived itself.
 *
 * A hold with another membership, such as new network settings, starts the session over
 * with it; the leases carry over. A pass joins the session that runs as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // The leases, publication and exchanges of one session registry.
class KeyguardSessionRegistry(
    private val dependencies: KeyguardSessionDependencies,
    private val connectivityService: ConnectivityService,
    private val exchangeDeadlineMs: Long = EXCHANGE_DEADLINE_MS,
) : KeyguardSessionLauncher, KeyguardSyncExchange {
    companion object {
        /** The hard bound of the exchange of a pass that no one holds the session for. */
        const val EXCHANGE_DEADLINE_MS: Long = 45_000L

        /** How long a pass waits for the peers of a held session to acknowledge its state. */
        const val LIVE_ACK_TIMEOUT_MS: Long = 10_000L

        /** How long the sessions get to say BYE once the last lease is gone. */
        private const val CLOSE_TIMEOUT_MS: Long = 3_000L
    }

    /** The leases of a group, and the runner of its session. */
    private class Group(
        val membership: P2pMembership,
    ) {
        var holds = 0
        var exchanges = 0

        /** `true` while someone holds the session. */
        val live = MutableStateFlow(false)

        lateinit var runner: Job
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [groups] and [closings]. */
    private val mutex = Mutex()

    /** The leased groups, by group id. */
    private val groups = mutableMapOf<String, Group>()

    /** The last session that was told to end, by group id: the next one waits for it. */
    private val closings = mutableMapOf<String, Job>()

    private val managers = MutableStateFlow<Map<String, KeyguardSessionManager>>(emptyMap())

    /** The sync status of every group with a running session, by group id. */
    val statuses: Flow<Map<String, KeyguardSessionStatus>> = managers.flatMapLatest { map ->
        if (map.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val flows = map.map { (groupId, manager) ->
                manager.status.map { status -> groupId to status }
            }
            combine(flows) { it.toMap() }
        }
    }

    /** Returns the session manager of [groupId], if one runs. */
    fun get(groupId: String): KeyguardSessionManager? = managers.value[groupId]

    /** The sync status of [groupId], or `null` while no session runs. */
    fun status(groupId: String): Flow<KeyguardSessionStatus?> = statuses
        .map { it[groupId] }
        .distinctUntilChanged()

    override suspend fun hold(
        groupId: String,
        membership: P2pMembership,
    ): Nothing {
        acquire(groupId, membership, hold = true)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                release(groupId, hold = true)
            }
        }
    }

    override suspend fun requestResend(groupId: String, peers: Set<String>?) {
        get(groupId)?.requestResend(peers)
    }

    /**
     * Keeps the current manager and its publication targets leased before a roster edit.
     * In particular, self-revocation may release the foreground hold before it is sent.
     * Returns `null` when there is no live manager to publish through.
     */
    suspend fun retainForPublication(groupId: String): KeyguardSessionPublication? = mutex.withLock {
        val group = groups[groupId] ?: return@withLock null
        val manager = get(groupId) ?: return@withLock null
        val publish = manager.publication()
        group.exchanges += 1
        object : KeyguardSessionPublication {
            private val released = MutableStateFlow(false)

            override suspend fun publish(timeoutMs: Long): Boolean = publish.invoke(timeoutMs)

            override suspend fun close() {
                if (released.compareAndSet(expect = false, update = true)) {
                    withContext(NonCancellable) { release(groupId, hold = false)?.join() }
                }
            }
        }
    }

    /**
     * Hands the state of the group to its peers over the session that runs, or one that
     * starts for it. A held session syncs on its own: the pass waits a little for the
     * acknowledgements. Otherwise the pass syncs with every peer that shows up within
     * [exchangeDeadlineMs], and the session ends with the pass unless it is held by then.
     * Returns `true` if it did.
     */
    override suspend fun exchange(
        groupId: String,
        membership: P2pMembership,
    ): Boolean {
        // Without a network the relay is out of reach; do not wait for it to say so. A
        // running session reconnects on its own once there is one.
        if (get(groupId) == null && !connectivityService.isInternetAvailable()) {
            return false
        }
        val group = acquire(groupId, membership, hold = false)
        var end: Job? = null
        try {
            exchangeOver(groupId, group)
        } finally {
            end = withContext(NonCancellable) {
                release(groupId, hold = false)
            }
        }
        // A pass that ends the session returns once the peers were told.
        end?.join()
        return end != null
    }

    private suspend fun exchangeOver(
        groupId: String,
        group: Group,
    ) {
        if (group.live.value) {
            // The held session hands the new state over, now or once it connects.
            get(groupId)?.publish(LIVE_ACK_TIMEOUT_MS)
            return
        }
        withTimeoutOrNull(exchangeDeadlineMs) {
            managers
                .map { it[groupId] }
                .distinctUntilChanged()
                // A session that starts over, such as for a new membership, is waited for anew.
                .mapLatest { manager -> manager?.awaitSettled() != null }
                .first { it }
        }
    }

    /** Adds a lease of the group [groupId], and starts its session if none runs. */
    private suspend fun acquire(
        groupId: String,
        membership: P2pMembership,
        hold: Boolean,
    ): Group = mutex.withLock {
        val current = groups[groupId]
        val group = if (current != null && (current.membership == membership || !hold)) {
            current
        } else {
            // The leases of a session with another membership carry over to the new one.
            val after = current?.let { close(groupId, it.runner) } ?: closings[groupId]
            Group(membership).also { group ->
                group.holds = current?.holds ?: 0
                group.exchanges = current?.exchanges ?: 0
                group.runner = launchRunner(groupId, group, after)
                groups[groupId] = group
            }
        }
        if (hold) group.holds += 1 else group.exchanges += 1
        group.live.value = group.holds > 0
        group
    }

    /** Removes a lease of the group [groupId]; returns the end of its session if it was the last one. */
    private suspend fun release(
        groupId: String,
        hold: Boolean,
    ): Job? = mutex.withLock {
        val group = groups[groupId]
            ?: return null
        if (hold) group.holds -= 1 else group.exchanges -= 1
        group.live.value = group.holds > 0
        if (group.holds + group.exchanges > 0) return null
        groups.remove(groupId)
        close(groupId, group.runner)
    }

    /** Runs the session of [group], after the previous one ended, until cancelled. */
    private fun launchRunner(
        groupId: String,
        group: Group,
        after: Job?,
    ): Job = scope.launch {
        after?.join()
        ReconnectBackoff().withRunForever {
            // The relay is out of reach without a network.
            connectivityService.awaitAvailable()
            val manager = KeyguardSessionManager(
                groupId = groupId,
                membership = group.membership,
                dependencies = dependencies,
                live = group.live,
            )
            managers.update { it + (groupId to manager) }
            try {
                manager.run()
            } finally {
                managers.update { map ->
                    if (map[groupId] === manager) map - groupId else map
                }
            }
        }
    }

    /** Ends the session that [runner] runs; the peers are told, rather than time out. Called under [mutex]. */
    private fun close(
        groupId: String,
        runner: Job,
    ): Job {
        // One session of the group at a time, even while they end.
        val previous = closings[groupId]
        return scope.launch {
            previous?.join()
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                get(groupId)?.closeSessions()
            }
            runner.cancelAndJoin()
        }.also { closings[groupId] = it }
    }

    /** Ends every session at once, as the vault locks. */
    fun close() {
        scope.cancel()
    }
}
