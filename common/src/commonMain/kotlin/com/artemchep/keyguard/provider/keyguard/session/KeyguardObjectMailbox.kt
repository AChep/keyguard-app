package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxCodec
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxOpening
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxPolicy
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxRejection
import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import com.artemchep.keyguard.provider.keyguard.transport.GroupObjects
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailbox
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxRef
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import com.artemchep.keyguard.provider.keyguard.transport.SealOpening
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Relay storage of the blobs of one sync group, see [KeyguardMailbox]: the relay keeps one
 * object per blob for every device of the group, under the object key of the group, see
 * [GroupObjects]. One worker serves the group, over the connection of any room that is
 * joined to a relay that keeps items; the rooms share one connection.
 *
 * - **Pull.** While this device allows relay storage, the relay is one more source of the
 *   [P2pGroupBlobs] of the group under each object key, see [relaySourceOf], that may hold
 *   every blob this device wants. It pulls what the group hands it, alongside the live
 *   sessions. A blob the relay lacks, or holds broken, goes into [missing], which the state
 *   items name for every peer, and is passed over for a while. A broken object is deleted,
 *   so that a device that holds the blob writes it again. One of a format this build does
 *   not read, or larger than it takes, stays for the devices that do.
 * - **Push.** Once the group allows relay storage, it leaves the blobs this device holds
 *   that a peer named missing, and the ones it holds that are not [settled], such as a
 *   wordlist the user added, also while offline: an offline device gets them on its next
 *   visit. A blob the relay holds already is not left again, and one another device left
 *   meanwhile counts as left. An object that opens stays after a fetch: the objects are
 *   shared, and leave by their lifetime.
 *
 * A new object key names every object anew, so the work starts over with it: what the
 * relay holds is unknown, the blobs this device holds then count as [settled] but the ones
 * it still had to leave, and the ones it misses are looked for again. The objects under the old key leave by their
 * lifetime. Without an object key the worker makes no request.
 *
 * A device with relay storage off makes no request, unless the relay may still hold what
 * it left: then it deletes the objects of the blobs it holds under the current key, once,
 * see [KeyguardMailbox.isRetiring].
 *
 * The state of the work is touched by [run] alone; the peers hand it what they learn with
 * [onPeerMissing], and their connections with [joined].
 */
// The steps of relay storage of the blobs.
@Suppress("TooManyFunctions")
internal class KeyguardObjectMailbox(
    private val mailbox: KeyguardMailbox,
) {
    companion object {
        /**
         * The relay as a source of blobs under the object key of [keys], see
         * [P2pGroupBlobs.claimNext]: never a device id. A new key names other objects, so a
         * blob the relay lacked under the old key is not passed over under the new one.
         */
        fun relaySourceOf(keys: GroupObjects): String = "relay:${keys.keyId}"

        /** The rejections of an object that is not the object of its blob, see [Serving.isBroken]. */
        private val BROKEN = setOf(MailboxRejection.MALFORMED, MailboxRejection.WRONG_HASH)
    }

    /**
     * The relay holds an object, as it told at [checkedAt] by the clock of the mailbox,
     * until [expiresAt] in epoch milliseconds, if it said.
     */
    private class Known(
        val checkedAt: Long,
        val expiresAt: Long?,
    )

    /** How the pull of one object went. */
    private sealed interface ObjectPull {
        /** The blob arrived: its [bytes], from an object that lives until [expiresAt] in Unix seconds, if known. */
        class Received(val bytes: ByteArray, val expiresAt: Long?) : ObjectPull

        /** The relay holds no such object, or not one that opens as the blob asked for. */
        data object Missing : ObjectPull

        /** The request failed with [result]. */
        class Failed(val result: RelayMailboxResult) : ObjectPull
    }

    /** The joined connections of the rooms, by peer. */
    private val joins = MutableStateFlow<Map<String, KeyguardMailboxJoin>>(emptyMap())

    /** The blobs each peer misses, as its latest state said, by peer. */
    private val peerMissing = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private val missingSink = MutableStateFlow<List<String>>(emptyList())

    /**
     * The blobs this device wants that the relay lacked at its last try, oldest first, see
     * [MailboxPolicy.withMissing]: the state items name them for every peer.
     */
    val missing: StateFlow<List<String>> = missingSink.asStateFlow()

    /** Wakes [run] once for any number of changes. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** What the relay holds, by blob, as far as this device knows. */
    private val present = mutableMapOf<String, Known>()

    /** The blobs whose objects are larger than the relay takes. */
    private val tooLarge = mutableSetOf<String>()

    /**
     * The blobs this device needs not leave on its own under the current object key, kept
     * across sessions, see [KeyguardRelayStorageUse.settledBlobs]: the ones it left, found
     * on the relay or could not leave, and the ones it held when it first served the group
     * under the key, which are left once a peer misses them. Every other blob it holds is
     * left.
     */
    private val settled = mutableSetOf<String>()

    /** [settled] as last stored. */
    private var savedSettled: Set<String>? = null

    private val retry = KeyguardMailboxRetry(mailbox)

    /** `true` once the objects of this device are deleted, see [KeyguardMailbox.isRetiring]. */
    private var retired = false

    /** The room of [peerId] joined the relay over the connection of [join]. */
    fun joined(peerId: String, join: KeyguardMailboxJoin) {
        joins.update { it + (peerId to join) }
    }

    /** The room of [peerId] left the connection of [join]. */
    fun left(peerId: String, join: KeyguardMailboxJoin) {
        joins.update { if (it[peerId] === join) it - peerId else it }
    }

    /** The latest state of [peerId] says it misses the blobs [missing]. */
    fun onPeerMissing(peerId: String, missing: List<String>) {
        peerMissing.update { it + (peerId to missing) }
        wake()
    }

    /** [peerId] is served no longer. */
    fun onPeerGone(peerId: String) {
        peerMissing.update { it - peerId }
    }

    /** Looks for something to do: a peer fetched a state. */
    fun wake() {
        signal.trySend(Unit)
    }

    /**
     * Serves the objects with the latest [keys] of the group, at the [relay], for the
     * [blobs] of the group, over a joined connection while there is one; never returns.
     * New keys start the work over, see [forgetObjects]. Without keys there is no request
     * to make, so the first round counts as done.
     */
    suspend fun run(
        keys: Flow<GroupObjects?>,
        relay: RelayMailbox,
        blobs: P2pGroupBlobs?,
    ): Nothing {
        keys.collectLatest { current ->
            forgetObjects()
            if (current == null) {
                mailbox.markObjectsRound()
                return@collectLatest
            }
            if (blobs != null && mailbox.enabled) loadSettled(current, blobs)
            joins
                .map { it.values.firstOrNull() }
                .distinctUntilChanged()
                .collectLatest { join ->
                    join ?: return@collectLatest
                    try {
                        Serving(current, relay, blobs, join).serve()
                    } finally {
                        // The claims of the relay go to the other sources.
                        blobs?.onSessionEnded(relaySourceOf(current))
                    }
                }
        }
        awaitCancellation()
    }

    /**
     * Forgets what this device knows of the objects under the last keys, and the blobs it
     * missed there: the next keys name other objects. The blobs too large stay so.
     */
    private fun forgetObjects() {
        present.clear()
        settled.clear()
        savedSettled = null
        setMissing(emptyList())
    }

    /**
     * Reads [settled] under the object key of [keys]. The first time, they are the blobs
     * this device holds now. Under a new key, they are the ones it holds and had settled
     * under the old key: a blob it still had to leave, such as one added offline, is left
     * under the new key too.
     */
    private suspend fun loadSettled(keys: GroupObjects, blobs: P2pGroupBlobs) {
        val stored = mailbox.settledBlobs()
        if (stored?.keyId == keys.keyId) {
            settled += stored.hashes
            return
        }
        val held = runCatchingUnlessCancelled { blobs.held() }.getOrNull().orEmpty()
        settled += if (stored != null) held intersect stored.hashes else held
        // Stored at once, so a blob added before the group allows relay storage is left then.
        saveSettled(keys, held = held)
    }

    /**
     * Stores [settled] under the object key of [keys], without the blobs this device holds
     * no longer, if it changed.
     */
    private suspend fun saveSettled(keys: GroupObjects, held: Set<String>) {
        settled.retainAll(held)
        if (settled != savedSettled) {
            val snapshot = settled.toSet()
            mailbox.setSettledBlobs(keys.keyId, snapshot)
            savedSettled = snapshot
        }
    }

    private fun setMissing(next: List<String>) {
        if (next != missingSink.value) missingSink.value = next
    }

    /** Serves the objects over the connection of [join]. */
    private inner class Serving(
        private val keys: GroupObjects,
        private val relay: RelayMailbox,
        private val blobs: P2pGroupBlobs?,
        private val join: KeyguardMailboxJoin,
    ) {
        private val source = relaySourceOf(keys)

        suspend fun serve(): Nothing = coroutineScope {
            if (!mailbox.enabled) {
                if (!retired && mailbox.isRetiring() && retire()) {
                    retired = true
                    mailbox.markObjectsRetired()
                }
                awaitCancellation()
            }
            launch { watch() }
            retry.loop(
                step = ::step,
                onFirstStep = mailbox::markObjectsRound,
                awaitWork = { withTimeoutOrNull(mailbox.timings.recheckMs) { signal.receive() } },
            )
        }

        /**
         * Wakes the loop whenever there may be something to do: a local change, such as a
         * wordlist the user added, is left at once.
         */
        private suspend fun watch() {
            val gate = mailbox.gate
                .map { it?.ready }
                .distinctUntilChanged()
            val local = mailbox.localEnvelope
                .map { it?.contentHash }
                .distinctUntilChanged()
            merge(gate, local, blobs?.version ?: emptyFlow())
                .collect { signal.trySend(Unit) }
        }

        /**
         * Does one round of work until a request fails; returns `false` if one did. It pulls
         * blobs before it leaves them: a pull cut short goes on from the bytes it got on the
         * next visit, see [KeyguardObjectDownloads], so the pulls finish within a few short
         * background syncs, see [KeyguardMailbox.settling]. An upload starts over each time,
         * so one that a background sync is too short for would hold every pull back.
         */
        private suspend fun step(): Boolean {
            val blobs = blobs ?: return true
            return pull(blobs) && (!mailbox.isOpen() || push(blobs))
        }

        private fun refOf(hash: String) = RelayMailboxRef(join.ticket, MailboxKind.BLOB, keys.id(hash))

        /** The relay holds the object of [hash] until [expiresAt], in Unix seconds, if known. */
        private fun learn(hash: String, expiresAt: Long?) {
            present[hash] = Known(checkedAt = mailbox.clock(), expiresAt = expiresAt?.times(MILLIS_PER_SECOND))
            settled += hash
        }

        //
        // Pull
        //

        /** Pulls the blobs the group hands the relay, one at a time. */
        private suspend fun pull(blobs: P2pGroupBlobs): Boolean {
            val wanted = blobs.wanted()
            // The blobs that arrived, or are no longer needed, are missing no more.
            setMissing(missing.value.filter { it in wanted })
            blobs.onPeerBlobs(source, wanted)
            val tried = mutableSetOf<String>()
            var ok = true
            // A fetch cut short goes on first: the group holds one download only.
            val resume = mailbox.objectDownloads.heldItem(mailbox.groupId)
                ?.let { item -> wanted.firstOrNull { refOf(it).item == item } }
            var hash = blobs.claimNext(source, resume)
            while (ok && hash != null) {
                if (tried.add(hash)) {
                    ok = pullOne(blobs, hash)
                    hash = if (ok) blobs.claimNext(source) else null
                } else {
                    // The group hands the same blob again: it waits for the next round.
                    blobs.onFailed(source, hash)
                    hash = null
                }
            }
            return ok
        }

        /** Pulls the claimed blob [hash]; the claim is settled whatever happens. */
        private suspend fun pullOne(blobs: P2pGroupBlobs, hash: String): Boolean {
            var pulled: ObjectPull? = null
            try {
                pulled = fetch(blobs, hash)
            } finally {
                if (pulled == null) blobs.onFailed(source, hash)
            }
            return when (val outcome = checkNotNull(pulled)) {
                is ObjectPull.Received -> {
                    blobs.onReceived(source, hash, outcome.bytes)
                    learn(hash, outcome.expiresAt)
                    setMissing(missing.value - hash)
                    true
                }

                // The peers are asked for it, and it is looked for again later.
                ObjectPull.Missing -> {
                    blobs.onFailed(source, hash)
                    setMissing(MailboxPolicy.withMissing(missing.value, hash))
                    true
                }

                is ObjectPull.Failed -> {
                    blobs.onFailed(source, hash)
                    retry.failed(outcome.result)
                }
            }
        }

        /**
         * Fetches the object of [hash]. A fetch cut short, such as by the end of a background
         * sync, leaves the bytes it got for the next one, see [KeyguardObjectDownloads]; the
         * object opens only once it is whole.
         */
        private suspend fun fetch(blobs: P2pGroupBlobs, hash: String): ObjectPull {
            val ref = refOf(hash)
            val download = mailbox.objectDownloads.take(mailbox.groupId, ref.item)
            try {
                val result = relay.get(ref, ifNoneMatch = null, maxBytes = join.limits.blobBytes, download = download)
                // The object is gone: a new upload of it starts over.
                if (result == RelayMailboxResult.NotFound) download.reset()
                val found = result as? RelayMailboxResult.Found
                val sealed = found?.body
                val opening = sealed?.let { open(blobs, hash, it) }
                val bytes = (opening as? MailboxOpening.Valid)?.item
                if (opening != null && isBroken(opening)) discard(ref)
                return when {
                    found == null && result != RelayMailboxResult.NotFound -> ObjectPull.Failed(result)
                    found != null && bytes != null -> ObjectPull.Received(bytes, found.expiresAt)
                    else -> ObjectPull.Missing
                }
            } finally {
                // Only a download that is not whole yet is kept.
                mailbox.objectDownloads.keep(mailbox.groupId, download)
            }
        }

        /**
         * Opens the [sealed] object of the blob [hash]. A seal of a format this build does
         * not read, or one that opens to more than it takes, is refused as such.
         */
        private fun open(blobs: P2pGroupBlobs, hash: String, sealed: ByteArray): MailboxOpening<ByteArray> {
            val maxBlobSize = blobs.config.maxBlobSize
            val seal = keys.open(hash, sealed, maxSize = MailboxCodec.maxBlobItemSize(maxBlobSize))
            return when (seal) {
                is SealOpening.Opened ->
                    MailboxCodec.decodeBlob(seal.plaintext, hash, mailbox.crypto::sha256, maxBlobSize)

                SealOpening.Unsupported -> MailboxOpening.Rejected(MailboxRejection.UNSUPPORTED_VERSION)
                SealOpening.TooLarge -> MailboxOpening.Rejected(MailboxRejection.TOO_LARGE)
                SealOpening.Corrupt -> MailboxOpening.Rejected(MailboxRejection.MALFORMED)
            }
        }

        /**
         * `true` if the [opening] of an object shows that it is not the object of its blob,
         * in a format this build reads: its seal does not open, or it holds something else.
         * An object of another format, or larger than this build takes, may serve other
         * devices.
         */
        private fun isBroken(opening: MailboxOpening<ByteArray>): Boolean =
            opening is MailboxOpening.Rejected && opening.reason in BROKEN

        /**
         * Deletes the broken object of [ref], so that a device that holds its blob writes
         * it again once this device names the blob missing; a failure is ignored. Only the
         * devices that hold the object key can name the object.
         */
        private suspend fun discard(ref: RelayMailboxRef) {
            runCatchingUnlessCancelled { relay.delete(ref) }
        }

        //
        // Push
        //

        /**
         * Leaves the blobs this device holds that a peer misses, and the ones that are not
         * [settled], unless the relay holds them already.
         */
        private suspend fun push(blobs: P2pGroupBlobs): Boolean {
            val held = blobs.held()
            val asked = peerMissing.value.values.flatten()
            val done = (asked + (held - settled))
                .filter { it in held && it !in tooLarge }
                .distinct()
                .sorted()
                .all { hash -> pushOne(blobs, hash) }
            saveSettled(keys, held)
            return done
        }

        /** Leaves the blob [hash], unless the relay holds it already. */
        private suspend fun pushOne(blobs: P2pGroupBlobs, hash: String): Boolean {
            if (isKnown(hash)) {
                settled += hash
                return true
            }
            val ref = refOf(hash)
            return when (val head = relay.head(ref)) {
                is RelayMailboxResult.Found -> {
                    learn(hash, head.expiresAt)
                    true
                }

                // The gate may have closed while the relay was asked.
                RelayMailboxResult.NotFound -> !mailbox.isOpen() || upload(blobs, hash, ref)
                else -> retry.failed(head)
            }
        }

        /**
         * `true` if the relay holds the object of [hash], as it told within the last
         * [KeyguardMailboxTimings.relayRecheckMs], and it has not expired: the relay is
         * asked again otherwise. An object is written once, so asking before it expires
         * would change nothing.
         */
        private fun isKnown(hash: String): Boolean {
            val known = present[hash] ?: return false
            val expired = known.expiresAt?.let { mailbox.now().toEpochMilliseconds() >= it } == true
            return mailbox.clock() - known.checkedAt < mailbox.timings.relayRecheckMs && !expired
        }

        /** Writes the object of [hash] once: one another device wrote meanwhile counts as written. */
        private suspend fun upload(blobs: P2pGroupBlobs, hash: String, ref: RelayMailboxRef): Boolean {
            val bytes = blobs.read(hash) ?: return true
            val sealed = keys.seal(hash, MailboxCodec.encodeBlob(bytes))
            val result = when {
                sealed.size > join.limits.blobBytes -> RelayMailboxResult.TooLarge
                // The gate may have closed while the blob was read.
                !mailbox.isOpen() -> null
                else -> relay.put(ref, keys.tag(hash), join.ttlSeconds, sealed, ifAbsent = true)
            }
            return when (result) {
                null -> true

                is RelayMailboxResult.Done -> {
                    learn(hash, result.expiresAt)
                    mailbox.recordUse(join)
                    true
                }

                RelayMailboxResult.Exists -> {
                    learn(hash, expiresAt = null)
                    true
                }

                RelayMailboxResult.TooLarge -> {
                    tooLarge += hash
                    settled += hash
                    true
                }

                else -> retry.failed(result)
            }
        }

        //
        // Clean-up
        //

        /**
         * Deletes the objects of the blobs this device holds, once it turned relay storage
         * off; returns `true` if each one is gone. What fails is tried again on the next
         * join, and otherwise waits out its time on the relay.
         */
        private suspend fun retire(): Boolean = runCatchingUnlessCancelled {
            blobs?.held().orEmpty().all { hash ->
                relay.delete(refOf(hash)).isGone
            }
        }.getOrDefault(false)
    }
}
