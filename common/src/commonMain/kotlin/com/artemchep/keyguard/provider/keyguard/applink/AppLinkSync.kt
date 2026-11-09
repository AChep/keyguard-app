package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.provider.keyguard.KeyguardRosterAlerts
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardInbox
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncExchange
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/** Runs the sync of linked apps: the [AppLinkWorker] holds its live session. */
interface AppLinkLauncher {
    /** Emits the live session while this app is linked to other devices, see [AppLinkService.observeLive]. */
    fun live(): Flow<AppLinkLive?>

    /** Queues a sync pass. */
    fun queueSync(): IO<Unit>
}

/**
 * Runs the sync passes of linked apps, one at a time: merges what the other devices sent,
 * then hands this device's state to the ones that are online. Lives as long as the vault
 * session; [close] it with the session.
 *
 * A pass asks a peer for its roster and envelope again, see
 * [KeyguardSessionLauncher.requestResend], when it could not apply them, and once the writes
 * of the peer it dropped as too far in the future are due.
 */
// A holder of what a pass needs.
@Suppress("LongParameterList")
class AppLinkSync internal constructor(
    private val db: VaultDatabaseManager,
    private val crypto: P2pCrypto,
    private val inbox: KeyguardInbox,
    private val exchange: () -> KeyguardSyncExchange,
    /** The sessions, which ask their peers to send their state again. */
    private val sessions: () -> KeyguardSessionLauncher,
    private val service: AppLinkService,
    private val categories: AppSyncCategories,
    private val switches: AppSyncSwitches,
    private val blobs: AppLinkBlobs,
    private val logRepository: LogRepository,
    private val rosterAlerts: KeyguardRosterAlerts = KeyguardRosterAlerts.NONE,
    private val now: () -> Instant = { Clock.System.now() },
    /** The most words of wordlists a pass writes, see [AppSyncWriteBudget]. */
    private val maxWordsPerPass: Int = WordlistBlob.MAX_WORDS,
) : AppLinkLauncher {
    companion object {
        private const val TAG = "AppLinkSync"

        private const val GROUP_ID = P2pGroupId.APP_LINK_ID
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** When a peer is asked again for the writes this device dropped as too far in the future, by device id. */
    private class DueWrites(
        val at: Long,
        val job: Job,
    )

    private val dueWritesLock = SynchronizedObject()

    private val dueWrites = mutableMapOf<String, DueWrites>()

    /** The object key the last pass left, see [AppLinkSyncPass.objectKey]. */
    @Volatile
    private var objectKey: ObjectKey? = null

    init {
        scope.launch {
            for (request in requests) {
                logRepository.bestEffort(TAG, "sync linked apps") { pass() }
            }
        }
    }

    override fun live(): Flow<AppLinkLive?> = service.observeLive()

    override fun queueSync(): IO<Unit> = ioEffect {
        requests.trySend(Unit)
    }

    /** Stops the passes. */
    fun close() {
        requests.close()
        scope.cancel()
    }

    /** Runs a sync pass. Returns `false` if the pass had no one to exchange with. */
    internal suspend fun pass(): Boolean {
        val membership = service.membership()
            ?: return false
        val first = materialize(membership)
        var deferred = first.deferred
        val exchanged = first.canExchange
        if (exchanged) {
            val ended = exchange().exchange(GROUP_ID, membership)
            // Merge what the other devices sent during the exchange.
            if (!inbox.peek(GROUP_ID).isEmpty) {
                val wanted = blobs.wanted()
                val key = objectKey
                val merged = materialize(membership)
                deferred = merged.deferred || deferred
                // The session ended before the merge named the blobs of the new records, or
                // the new object key the relay holds the wanted ones under: one more
                // exchange fetches them now, not in the next pass.
                val missing = blobs.wanted()
                val fetch = !wanted.containsAll(missing) || objectKey != key && missing.isNotEmpty()
                if (ended && fetch && merged.canExchange) {
                    exchange().exchange(GROUP_ID, membership)
                    // Writes the records of the blobs that arrived.
                    deferred = materialize(membership).deferred || deferred
                }
            }
        }
        // The records that did not fit go into the next pass.
        if (deferred) requests.trySend(Unit)
        return exchanged
    }

    /** What a pass of [materialize] found. */
    internal class Materialized(
        /** `true` if a record did not fit, see [AppSyncWriteBudget]: a next pass writes it. */
        val deferred: Boolean,
        /**
         * `true` if this app still has the link the pass ran for, and someone to exchange
         * with, see [AppLinkSyncPass.canExchange].
         */
        val canExchange: Boolean,
    )

    /** What a pass of [materialize] left to do. */
    private class Merged(
        val missing: Set<String>,
        val rosterChange: Pair<Roster, Roster>?,
        val deferred: Boolean,
        val dueFutureWrites: Map<String, Long>,
        val objectKey: ObjectKey?,
        val canExchange: Boolean,
    )

    /**
     * Merges the rosters and envelopes the [inbox] holds, as the device with the
     * [membership], and writes the records whose blobs are here now. Then hands the blobs that
     * are still missing to the [blobs]. A record that did not fit, see [AppSyncWriteBudget],
     * waits for a next pass, see [Materialized.deferred]. If the merge of the rosters is
     * refused, no envelope of the batch is applied.
     *
     * A pass that fails drops what it took from the [inbox] and asks its senders for it
     * again: a failure that persists ends with the budget of their sessions.
     */
    internal suspend fun materialize(membership: P2pMembership): Materialized {
        val batch = inbox.peek(GROUP_ID)
        val enabled = switches.enabledNow()
        val outcome = runCatchingUnlessCancelled {
            db.mutate(TAG) { database ->
                // Commit complete records and their sync state before notifying observers.
                database.transactionWithResult {
                    // A pass of a link that was left, or replaced, while it ran writes
                    // nothing: what it holds is of another group.
                    val current = database.appLinkMembershipOrNull()
                    if (current == null || !current.isSameLink(membership)) return@transactionWithResult null
                    // This device first: if its build supports other caps than its record
                    // lists, or the user turned relay storage on or off, the exchange after
                    // the pass hands the new record to the others.
                    val roster = KeyguardSyncStore(database, GROUP_ID).loadValidated(membership, crypto)
                    val refreshed = roster.refreshSelfCaps(now, relayStorage = membership.network.relayStorage)
                    val pass = AppLinkSyncPass(
                        database = database,
                        membership = membership,
                        crypto = crypto,
                        categories = categories,
                        enabled = enabled,
                        now = now,
                        maxWords = maxWordsPerPass,
                        logRepository = logRepository,
                        // A new record of this device is validated again.
                        roster = roster.takeUnless { refreshed },
                    )
                    // The roster first: it names the writers of the entries.
                    val adopted = batch.roster?.let(pass::applyRoster) ?: true
                    if (adopted) {
                        batch.envelopes.forEach { pass.applyEnvelope(it.from, it.envelope) }
                    } else {
                        // The stored roster lacks the revocations the refused one brings.
                        logRefusedRoster(batch.senders)
                    }
                    // Records that arrived from an older build, or were never recorded.
                    pass.publishUnrecorded()
                    // After the roster: a removal this pass learned of, or made, needs a new key.
                    pass.ensureObjectKey()
                    pass.materializeUnwritten()
                    Merged(
                        missing = pass.missingBlobs(),
                        rosterChange = pass.rosterChange,
                        deferred = pass.deferred,
                        dueFutureWrites = pass.dueFutureWrites,
                        objectKey = pass.objectKey,
                        canExchange = pass.canExchange,
                    )
                }
            }.bind()
        }
        inbox.acknowledge(GROUP_ID, batch)
        val merged = outcome.getOrElse { e ->
            if (batch.senders.isNotEmpty()) sessions().requestResend(GROUP_ID, batch.senders)
            throw e
        } ?: return Materialized(deferred = false, canExchange = false)
        askAgainWhenDue(merged.dueFutureWrites)
        objectKey = merged.objectKey
        blobs.onWanted(merged.missing)
        val rosterChange = merged.rosterChange
        // Away from the database, and the pass: posting may wait for a permission prompt.
        if (rosterChange != null) {
            scope.launch {
                logRepository.bestEffort(TAG, "alert of roster changes") {
                    rosterAlerts.onRosterChanged(membership, rosterChange.first, rosterChange.second)
                }
            }
        }
        return Materialized(deferred = merged.deferred, canExchange = merged.canExchange)
    }

    /** Logs that a pass refused the roster of the [senders], and the entries that came with it. */
    private fun logRefusedRoster(senders: Set<String>) {
        val message = "Refused the roster of devices ${senders.joinToString()}, which would be too large, " +
            "and the entries that came with it"
        logRepository.post(TAG, message, LogLevel.WARNING)
    }

    /**
     * Asks each device of [due] for its roster and envelope again once the writes of it
     * this pass dropped as too far in the future are due, at the local time [due] names: by
     * then the same writes are taken. Applying an envelope again is allowed for it.
     */
    private fun askAgainWhenDue(due: Map<String, Long>) {
        if (due.isEmpty()) return
        val nowMs = now().toEpochMilliseconds()
        synchronized(dueWritesLock) {
            due.forEach { (from, at) ->
                val scheduled = dueWrites[from]
                if (scheduled == null || !scheduled.job.isActive || at < scheduled.at) {
                    scheduled?.job?.cancel()
                    val job = scope.launch {
                        delay((at - nowMs).coerceAtLeast(0L))
                        logRepository.bestEffort(TAG, "ask for writes that are due") {
                            sessions().requestResend(GROUP_ID, setOf(from))
                        }
                    }
                    dueWrites[from] = DueWrites(at, job)
                }
            }
        }
    }
}

/** Runs [block]; a failure is logged under [tag] and yields `null`. */
private suspend fun <T> LogRepository.bestEffort(
    tag: String,
    what: String,
    block: suspend () -> T,
): T? = runCatchingUnlessCancelled {
    block()
}.getOrElse { e ->
    post(tag, "Failed to $what: $e", LogLevel.WARNING)
    null
}

/** `true` if this is the membership of the same device in the same group as [other]. */
private fun P2pMembership.isSameLink(other: P2pMembership): Boolean =
    groupId == other.groupId && device.deviceId == other.device.deviceId
