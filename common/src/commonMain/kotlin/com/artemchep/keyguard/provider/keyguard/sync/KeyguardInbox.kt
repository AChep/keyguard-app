package com.artemchep.keyguard.provider.keyguard.sync

import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterCrdt
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The rosters and envelopes received from the other devices of a sync group that the next
 * sync pass applies. Lives as long as the vault session: anything not applied when the
 * vault locks is sent again by its peer.
 *
 * An envelope holds the whole synced state of its sender, which only grows, so only the
 * newest one of each device and [Source] waits: what the inbox holds follows the devices of
 * the group, not how fast they send. A device may send over a session and leave its state
 * on the relay, and the one the relay hands over may be older than the one the session
 * did: each source waits in a slot of its own, so an older envelope never displaces a
 * newer one before a pass applies it. A pass applies both: merging is idempotent and
 * commutative. An envelope with the content of one that waits for the same device is not
 * queued again; one that a pass applied before is: a later pass may take more of it, such
 * as once a category is on again. The rosters wait in one slot of their own, merged, so a
 * pass adopts them before it applies the entries they vouch for.
 */
class KeyguardInbox {
    /** How an envelope arrived. */
    enum class Source {
        /** Over a live session with the device. */
        SESSION,

        /** From the relay, which kept it while the device was offline. */
        RELAY,
    }

    /** The envelope [envelope] the device [from] sent, with the content hash [hash], if known. */
    class Received(
        val from: String,
        val envelope: Envelope,
        val hash: String?,
        val source: Source = Source.SESSION,
    )

    /**
     * What waits for a sync pass: the [roster] the sessions took, merged, or `null`, and
     * the newest envelope of each device.
     */
    class Batch(
        val roster: Roster?,
        val envelopes: List<Received>,
        /** The devices whose rosters [roster] holds. */
        rosterSenders: Set<String>,
    ) {
        /** The devices that sent something of the batch. */
        val senders: Set<String> = rosterSenders + envelopes.map { it.from }

        val isEmpty: Boolean get() = roster == null && envelopes.isEmpty()
    }

    /** The merged roster [roster] that the devices [senders] sent. */
    private class PendingRoster(
        val roster: Roster,
        val senders: Set<String>,
    )

    /** What waits for the next sync pass of one group. */
    private class Queue {
        /** The newest envelope of each device and source. */
        val envelopes = mutableMapOf<Pair<String, Source>, Received>()

        var roster: PendingRoster? = null

        val isEmpty: Boolean get() = envelopes.isEmpty() && roster == null
    }

    private val lock = SynchronizedObject()

    private val queues = mutableMapOf<String, Queue>()

    /**
     * Queues the [envelope] the device [from] sent for the group [groupId], as the [source]
     * handed it over, in place of the one that waits from the same device and source,
     * unless an envelope of the device with the same content [hash] waits. Returns `true`
     * if it was queued.
     */
    fun post(
        groupId: String,
        from: String,
        envelope: Envelope,
        hash: String? = null,
        source: Source = Source.SESSION,
    ): Boolean = synchronized(lock) {
        val queue = queues.getOrPut(groupId) { Queue() }
        val duplicate = hash != null && Source.entries.any { queue.envelopes[from to it]?.hash == hash }
        if (!duplicate) queue.envelopes[from to source] = Received(from, envelope, hash, source)
        !duplicate
    }

    /**
     * Merges the [roster] the device [from] sent into the one waiting for the group
     * [groupId]: every roster that arrives is a validated one, so the newer record of each
     * device wins, see [RosterCrdt.merge]. A roster of another founder replaces it.
     */
    fun postRoster(
        groupId: String,
        from: String,
        roster: Roster,
    ) {
        synchronized(lock) {
            val queue = queues.getOrPut(groupId) { Queue() }
            val waiting = queue.roster?.takeIf { it.roster.founder == roster.founder }
            queue.roster = if (waiting != null) {
                PendingRoster(RosterCrdt.merge(waiting.roster, roster), waiting.senders + from)
            } else {
                PendingRoster(roster, setOf(from))
            }
        }
    }

    /** Returns what waits for a sync pass of the group [groupId]: up to one envelope per device and source. */
    fun peek(
        groupId: String,
    ): Batch = synchronized(lock) {
        val queue = queues[groupId]
        val roster = queue?.roster
        Batch(
            roster = roster?.roster,
            envelopes = queue?.envelopes?.values?.toList().orEmpty(),
            rosterSenders = roster?.senders.orEmpty(),
        )
    }

    /**
     * Removes the [batch] once a sync pass committed it, or gave up on it. What was posted
     * since the pass started stays queued, in either slot of its device: only the very
     * envelopes the pass took go.
     */
    fun acknowledge(
        groupId: String,
        batch: Batch,
    ) {
        synchronized(lock) {
            val queue = queues[groupId] ?: return
            if (batch.roster != null && queue.roster?.roster === batch.roster) queue.roster = null
            queue.envelopes.values.removeAll { waiting -> batch.envelopes.any { it === waiting } }
            if (queue.isEmpty) queues.remove(groupId)
        }
    }
}
