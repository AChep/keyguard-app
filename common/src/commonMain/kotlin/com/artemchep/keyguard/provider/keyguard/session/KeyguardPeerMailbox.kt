package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxAddress
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxCodec
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxOpening
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxPolicy
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxState
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxStateDraft
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.provider.keyguard.transport.PairMailbox
import com.artemchep.keyguard.provider.keyguard.transport.RelayClient
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailbox
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxRef
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import com.artemchep.keyguard.provider.keyguard.transport.RelayState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Relay storage with one [peer], see [KeyguardMailbox]: the relay keeps the latest state of
 * each device for the other one, sealed with the [keys] of the pair. The blobs are the
 * group's, see [KeyguardObjectMailbox].
 *
 * While the room is joined, [run] does a round of work, then waits for something to do:
 *
 * - **Fetch.** It asks for the state of the peer when the room joins, then every
 *   [KeyguardMailboxTimings.fetchIntervalMs] while no live session with the peer is
 *   established, with the tag it merged last. What arrives goes to the inbox, see
 *   [KeyguardMailbox.deliver], and the blobs the peer misses to the objects.
 * - **Post.** It posts the state of this device: on each join, then
 *   [KeyguardMailboxTimings.postDebounceMs] after a local change or a change of the
 *   blobs this device misses. It skips the state a live session with the peer
 *   acknowledged, and one the relay holds already, by tag. Until the group allows relay
 *   storage, the state holds the roster only, with no entries and no blobs: the peers that
 *   turned relay storage on learn so from it, and the group opens without a live session.
 *   A peer with relay storage off never fetches it.
 *
 * A device with relay storage off makes no request, unless the relay may still hold what
 * it left while it was on: then it removes that once, see [retireOwn].
 *
 * The state of the work is touched by [run] alone, then by [retire]; [requestResend] only
 * raises a flag.
 */
// The steps of relay storage with one peer.
@Suppress("TooManyFunctions")
internal class KeyguardPeerMailbox(
    private val peer: RosterDevice,
    private val keys: PairMailbox,
    private val relay: RelayMailbox,
    private val mailbox: KeyguardMailbox,
) {
    /** What a state item is made of: the [local] state, the [roster] and the blobs this device misses. */
    private data class DraftKey(
        val local: LocalEnvelope?,
        val roster: Roster,
        val missing: List<String>,
    )

    /** A state item ready to post, with its [tag], and what it was made of. */
    private class Draft(
        val key: DraftKey,
        val draft: MailboxStateDraft,
        val tag: String,
    )

    /** The state items of the pair over one joined [connection] to the relay, and what it holds of them. */
    private inner class Join(
        val connection: KeyguardMailboxJoin,
    ) {
        val stateOut = RelayMailboxRef(connection.ticket, MailboxKind.STATE, keys.outbox.stateId)

        val stateIn = RelayMailboxRef(connection.ticket, MailboxKind.STATE, keys.inbox.stateId)

        /** The tag of the state of this device the relay holds, as a HEAD or a PUT told at [relayCheckedAt]. */
        private var relayTag: String? = null

        /** When the relay last told what it holds, by the clock of the mailbox; `null` if not on this connection. */
        private var relayCheckedAt: Long? = null

        /** When the state of this device on the relay expires, in epoch milliseconds, if the relay said. */
        private var relayExpiresAt: Long? = null

        /**
         * `true` while what the relay holds is known: it told within the last
         * [KeyguardMailboxTimings.relayRecheckMs]. The relay may trim an item at any time, so
         * it is asked again after that.
         */
        val knowsRelay: Boolean
            get() = relayCheckedAt?.let { mailbox.clock() - it < mailbox.timings.relayRecheckMs } == true

        /**
         * The tag of the state of this device the relay holds, or `null` if it is not known,
         * or the item there is about to expire, see [MailboxPolicy.isExpiring]: the state is
         * looked up, or posted, again then.
         */
        val freshRelayTag: String?
            get() {
                val expiring = MailboxPolicy.isExpiring(
                    expiresAtMs = relayExpiresAt,
                    nowMs = mailbox.now().toEpochMilliseconds(),
                    ttlMs = connection.ttlSeconds * MILLIS_PER_SECOND,
                    aheadPercent = mailbox.timings.refreshAheadPercent,
                )
                return relayTag.takeIf { knowsRelay && !expiring }
            }

        /** The relay holds the state with the [tag], or none if `null`, until [expiresAtMs], if known. */
        fun learn(tag: String?, expiresAtMs: Long?) {
            relayTag = tag
            relayExpiresAt = expiresAtMs
            relayCheckedAt = mailbox.clock()
        }

        /** When the state of the peer was last fetched, by the clock of the mailbox. */
        var fetchedAt: Long? = null

        val retry = KeyguardMailboxRetry(mailbox)

        /** When an item made now stops counting, in epoch milliseconds. */
        fun expiresAt(): Long = connection.expiresAtMs(mailbox.now())
    }

    private val stateAddress = MailboxAddress(item = keys.outbox.stateId, from = mailbox.selfId, to = peer.id)

    private val peerStateAddress = MailboxAddress(item = keys.inbox.stateId, from = peer.id, to = mailbox.selfId)

    /** Wakes [run] once for any number of changes. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** No state is posted before this time, by the clock of the mailbox: local changes are coalesced. */
    @Volatile
    private var postNotBefore = 0L

    /** `true` to fetch the whole state of the peer again, see [requestResend]. */
    @Volatile
    private var resend = false

    /** The tag of the state of the peer this device merged last. */
    private var peerTag: String? = null

    /** `true` once what this device left for the peer is removed, see [retireOwn]. */
    private var retiredOwn = false

    /** The tag of the state the relay refused as too large: it is not posted again until it changes. */
    private var refusedTag: String? = null

    private var draft: Draft? = null

    /** Fetches the whole state of the peer again, as a pass that could not apply it asks. */
    fun requestResend() {
        resend = true
        signal.trySend(Unit)
    }

    /**
     * Serves the peer over the joined [connection] to the relay, until cancelled. Its first
     * round marks the round of the peer, see [KeyguardMailbox.awaitFirstRound].
     */
    suspend fun run(connection: KeyguardMailboxJoin): Nothing {
        val join = Join(connection)
        // The objects of the group go over any joined connection.
        mailbox.objects.joined(peer.id, connection)
        try {
            coroutineScope {
                if (!mailbox.enabled) {
                    try {
                        if (!retiredOwn && mailbox.isRetiring() && retireOwn(join)) {
                            retiredOwn = true
                            mailbox.markPeerRetired(peer.id)
                        }
                    } finally {
                        mailbox.markRound(peer.id)
                    }
                    awaitCancellation()
                }
                launch { watch() }
                loop(join)
            }
        } finally {
            mailbox.objects.left(peer.id, connection)
        }
    }

    private suspend fun loop(join: Join): Nothing {
        postNotBefore = 0L
        join.retry.loop(
            step = { step(join) },
            onFirstStep = { mailbox.markRound(peer.id) },
            awaitWork = { awaitWork(join) },
        )
    }

    /** Wakes the loop whenever there may be something to do. */
    private suspend fun watch() {
        // A pull changes the blobs this device misses one at a time: they are coalesced too.
        val debounced = merge(
            mailbox.localEnvelope.filterNotNull().drop(1),
            mailbox.objects.missing.drop(1),
        ).map { postNotBefore = mailbox.clock() + mailbox.timings.postDebounceMs }
        val sessions = mailbox.progress
            .map { it[peer.id]?.let { p -> p.established to p.localHash.takeIf { p.upToDate } } }
            .distinctUntilChanged()
        val gate = mailbox.gate
            .map { it?.ready }
            .distinctUntilChanged()
        merge(debounced, sessions, gate)
            .collect { signal.trySend(Unit) }
    }

    /** Waits for a signal, or the next time something is due. */
    private suspend fun awaitWork(join: Join) {
        val now = mailbox.clock()
        val fetchAt = join.fetchedAt
            ?.plus(mailbox.timings.fetchIntervalMs)
            ?.takeUnless { isLive() }
        val due = listOfNotNull(
            fetchAt,
            postNotBefore.takeIf { it > now },
            now + mailbox.timings.recheckMs,
        ).min()
        withTimeoutOrNull((due - now).coerceAtLeast(0L)) { signal.receive() }
    }

    /** Does one round of work, in order, until a request fails; returns `false` if one did. */
    private suspend fun step(join: Join): Boolean {
        val live = isLive()
        val fetchDue = join.fetchedAt
            ?.let { mailbox.clock() - it >= mailbox.timings.fetchIntervalMs }
            ?: true
        val fetched = live || !(fetchDue || resend) || fetch(join)
        return fetched && post(join)
    }

    /** `true` while a live session with the peer is established: it syncs everything itself. */
    private fun isLive(): Boolean = mailbox.progress.value[peer.id]?.established == true

    //
    // Fetch
    //

    private suspend fun fetch(join: Join): Boolean {
        val again = resend
        resend = false
        val result = relay.get(
            ref = join.stateIn,
            ifNoneMatch = peerTag.takeUnless { again },
            maxBytes = join.connection.limits.stateBytes,
        )
        when (result) {
            RelayMailboxResult.NotModified -> Unit
            // The peer left nothing, or it expired or was evicted.
            RelayMailboxResult.NotFound -> peerTag = null
            is RelayMailboxResult.Found -> accept(result)
            else -> return join.retry.failed(result)
        }
        join.fetchedAt = mailbox.clock()
        // A peer may have left the objects this device misses since.
        mailbox.objects.wake()
        return true
    }

    private fun accept(found: RelayMailboxResult.Found) {
        val opening = found.body
            ?.let { keys.inbox.open(it, maxSize = MailboxCodec.maxStateSize(mailbox.maxEnvelopeSize).toLong()) }
            ?.let { plaintext ->
                MailboxCodec.decodeState(
                    bytes = plaintext,
                    expected = peerStateAddress,
                    nowMs = mailbox.now().toEpochMilliseconds(),
                    hasher = mailbox.crypto::sha256,
                    maxEnvelopeSize = mailbox.maxEnvelopeSize,
                )
            }
        // An item that does not open is not fetched again until it changes.
        peerTag = found.tag
        val state = (opening as? MailboxOpening.Valid<MailboxState>)?.item
            ?: return
        mailbox.deliver(peer.id, state)
        mailbox.objects.onPeerMissing(peer.id, state.missing)
    }

    //
    // Post
    //

    /**
     * Posts the state of this device, if it is due and the relay does not hold it: the whole
     * state once the group allows relay storage, else the roster only, see [announcement].
     * A peer that is not active gets nothing, see [isActive].
     */
    private suspend fun post(join: Join): Boolean {
        val local = mailbox.localEnvelope.value?.takeIf { isActive(it) }
        val roster = mailbox.gate.value?.roster
        if (mailbox.clock() < postNotBefore || local == null || roster == null) return true
        val acked = mailbox.progress.value[peer.id]
            ?.let { it.established && it.upToDate && it.localHash == local.contentHash } == true
        return if (acked && refusedTag == null) {
            // A live session took the state: there is nothing to post, nor to build, nor
            // to keep built.
            draft = null
            mailbox.setTooLarge(peer.id, false)
            true
        } else {
            val current = if (mailbox.isOpen()) wholeState(local, roster) else announcement(roster)
            val due = MailboxPolicy.shouldPost(current.tag, join.freshRelayTag, refusedTag, liveAcked = acked)
            if (!due) mailbox.setTooLarge(peer.id, current.tag == refusedTag)
            !due || postDue(join, current)
        }
    }

    /**
     * Posts [current], unless the relay holds it already and it is not about to expire:
     * the relay is asked first when what it holds is not known, see [Join.knowsRelay].
     */
    private suspend fun postDue(join: Join, current: Draft): Boolean {
        val head = if (join.knowsRelay) null else relay.head(join.stateOut)
        when (head) {
            is RelayMailboxResult.Found -> join.learn(head.tag, head.expiresAt?.times(MILLIS_PER_SECOND))
            RelayMailboxResult.NotFound -> join.learn(tag = null, expiresAtMs = null)
            else -> if (head != null) return join.retry.failed(head)
        }
        return join.freshRelayTag == current.tag || putState(join, current)
    }

    private suspend fun putState(join: Join, current: Draft): Boolean {
        // The gate may have closed while the relay was asked: the next round posts the
        // roster only, see [watch]. The peer may have been removed meanwhile too.
        val closed = current.key.local != null && !mailbox.isOpen()
        if (closed || !isActive(mailbox.localEnvelope.value)) return true
        val sealed = current.draft
            .takeIf { it.fits(mailbox.maxEnvelopeSize) }
            ?.let { seal(join, it) }
            ?.takeIf { it.size <= join.connection.limits.stateBytes }
        val result = if (sealed != null) {
            relay.put(join.stateOut, current.tag, join.connection.ttlSeconds, sealed)
        } else {
            RelayMailboxResult.TooLarge
        }
        return when (result) {
            is RelayMailboxResult.Done -> {
                val expiresAt = result.expiresAt?.times(MILLIS_PER_SECOND)
                    ?: join.expiresAt()
                join.learn(current.tag, expiresAt)
                refusedTag = null
                mailbox.setTooLarge(peer.id, false)
                mailbox.recordUse(join.connection)
                true
            }

            RelayMailboxResult.TooLarge -> refuse(current.tag)
            else -> join.retry.failed(result)
        }
    }

    /**
     * `true` if this device and the peer are both active in the roster of [local], see
     * [KeyguardMailbox.isActivePair]. The group writes a new object key once it removes a
     * device, and only after the removal, so a state item drafted and posted while both are
     * active never carries a key the peer must not get.
     */
    private fun isActive(local: LocalEnvelope?): Boolean = local != null && mailbox.isActivePair(local.roster, peer.id)

    /** The state with the [tag] is larger than the relay takes: the peer syncs live only until it changes. */
    private fun refuse(tag: String): Boolean {
        refusedTag = tag
        mailbox.setTooLarge(peer.id, true)
        return true
    }

    private fun seal(join: Join, draft: MailboxStateDraft): ByteArray =
        keys.outbox.seal(draft.encode(join.expiresAt()))

    /** The whole state of this device: its envelope, and the blobs it misses. */
    private fun wholeState(local: LocalEnvelope, roster: Roster): Draft =
        draftOf(local, roster, missing = mailbox.objects.missing.value)

    /**
     * The roster of this device alone, with no entries and no blobs: what goes to the relay
     * while the group does not allow relay storage, or once this device turned it off.
     */
    private fun announcement(roster: Roster): Draft =
        draftOf(local = null, roster = roster, missing = emptyList())

    /** Returns the state item of [local], made once for each change of what it holds. */
    private fun draftOf(
        local: LocalEnvelope?,
        roster: Roster,
        missing: List<String>,
    ): Draft {
        val key = DraftKey(local, roster, missing)
        val cached = draft
        if (cached != null && cached.key == key) return cached
        val draft = MailboxStateDraft(stateAddress, roster, local, missing)
        return Draft(key, draft, keys.outbox.tag(draft.tagContent))
            .also { this.draft = it }
    }

    //
    // Clean-up
    //

    /**
     * Removes what this device left for the peer while it had relay storage on, once it
     * turned it off: the state of the peer is deleted, and the state of this device is
     * replaced with one of its roster only, so the peer learns of the new record of this
     * device and stops too. Returns `true` if both went; what fails is tried again on the
     * next join, and otherwise waits out its time on the relay.
     */
    private suspend fun retireOwn(join: Join): Boolean = runCatchingUnlessCancelled {
        // The roster has to say so first: a sync pass signs the record of this device again.
        val gate = mailbox.gate
            .filterNotNull()
            .first { !it.selfListed }
        val notice = announcement(gate.roster)
        val sealed = seal(join, notice.draft)
            .takeIf { it.size <= join.connection.limits.stateBytes }
        val inbound = relay.delete(join.stateIn)
        val outbound = if (sealed != null) {
            relay.put(join.stateOut, notice.tag, join.connection.ttlSeconds, sealed)
        } else {
            relay.delete(join.stateOut)
        }
        listOf(inbound, outbound).all { it.isGone }
    }.getOrDefault(false)

    /**
     * Deletes what this device left for the peer, which left the group, and what the peer
     * left for it, once the [room] is joined to a relay that keeps items. Suspends until it
     * is; the caller bounds the wait.
     */
    suspend fun retire(room: RelayClient) {
        val joined = room.state
            .filterIsInstance<RelayState.Joined>()
            .first { it.ticket != null && it.limits.mailbox != null }
        val join = Join(KeyguardMailboxJoin(checkNotNull(joined.ticket), checkNotNull(joined.limits.mailbox)))
        relay.delete(join.stateOut)
        relay.delete(join.stateIn)
    }
}
