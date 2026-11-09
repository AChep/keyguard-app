package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.session.BlobExchangeConfig
import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import com.artemchep.keyguard.provider.keyguard.transport.monotonicMs
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The blobs that arrived and were verified, and that no record holds yet. They stay in
 * memory until a sync pass writes their records, see [AppLinkSyncPass.materializeUnwritten].
 */
internal class AppLinkReceivedBlobs {
    private val lock = SynchronizedObject()

    private val blobs = mutableMapOf<String, ByteArray>()

    fun put(hash: String, bytes: ByteArray) {
        synchronized(lock) { blobs[hash] = bytes }
    }

    fun get(hash: String): ByteArray? = synchronized(lock) { blobs[hash] }

    fun hashes(): Set<String> = synchronized(lock) { blobs.keys.toSet() }

    /** The bytes of every blob held. */
    fun size(): Long = synchronized(lock) { blobs.values.sumOf { it.size.toLong() } }

    /** Forgets every blob but the [hashes]. */
    fun retainAll(hashes: Set<String>) {
        synchronized(lock) { blobs.keys.retainAll(hashes) }
    }
}

/**
 * The blobs of linked apps, such as the words of the wordlists, see [AppSyncBlobCategory].
 * A blob is served from the local records that hold it, and kept in memory once it
 * arrives, until a sync pass writes its records: there is no blob store.
 *
 * Decides which blob each peer session pulls, so that every blob comes from one device at
 * a time. A device that could not hand a blob over is passed over for that blob for a
 * while.
 *
 * @param queueSync queues a sync pass, which writes the records of the blobs that arrived.
 * @param clock a monotonic clock in milliseconds.
 */
// A holder of the state the sessions share.
@Suppress("LongParameterList")
internal class AppLinkBlobs(
    private val db: VaultDatabaseManager,
    private val categories: AppSyncCategories,
    private val switches: AppSyncSwitches,
    private val received: AppLinkReceivedBlobs,
    private val queueSync: () -> IO<Unit>,
    private val clock: () -> Long = monotonicMs(),
    private val retryDelayMs: Long = PEER_RETRY_DELAY_MS,
    private val maxReceivedBytes: Long = MAX_RECEIVED_BYTES,
) : P2pGroupBlobs {
    companion object {
        /** 16 MiB, the largest blob: the words of a wordlist of over a million words. */
        const val MAX_BLOB_SIZE: Long = 16L * 1024L * 1024L

        /**
         * 64 MiB, the most bytes of the blobs that arrived and wait for their records, and of
         * the ones being pulled, see [claimNext].
         */
        const val MAX_RECEIVED_BYTES: Long = 4L * MAX_BLOB_SIZE

        /** 2 minutes. */
        const val PEER_RETRY_DELAY_MS: Long = 2L * 60L * 1_000L
    }

    override val config: BlobExchangeConfig = BlobExchangeConfig(
        maxBlobSize = MAX_BLOB_SIZE,
    )

    private val lock = SynchronizedObject()

    /** The blobs the records need and no record holds. */
    private var needed: Set<String> = emptySet()

    private val peerBlobs = mutableMapOf<String, Set<String>>()

    /** The peer each blob is being pulled from. */
    private val claims = mutableMapOf<String, String>()

    /** Until when a peer is not asked for a blob, by peer and blob. */
    private val passedOver = mutableMapOf<Pair<String, String>, Long>()

    private val versionSink = MutableStateFlow(0L)

    override val version: StateFlow<Long> = versionSink.asStateFlow()

    /**
     * The records need the blobs [hashes], which no record holds: called after each sync
     * pass. The blobs that arrived and are no longer needed are forgotten.
     */
    fun onWanted(hashes: Set<String>) {
        synchronized(lock) {
            needed = hashes
            passedOver.keys.retainAll { (_, hash) -> hash in hashes }
        }
        received.retainAll(hashes)
        // A pass may have written records: this device may hold other blobs now.
        bump()
    }

    override suspend fun held(): Set<String> {
        val enabled = switches.enabledNow()
        val database = db.get().bind()
        return categories.blobs
            .filter { it.category in enabled }
            .flatMapTo(mutableSetOf()) { it.held(database) }
    }

    override suspend fun read(hash: String): ByteArray? {
        val enabled = switches.enabledNow()
        val database = db.get().bind()
        return categories.blobs
            .filter { it.category in enabled }
            .firstNotNullOfOrNull { it.readBlob(database, hash) }
    }

    override fun onPeerBlobs(peerId: String, hashes: Collection<String>) {
        val set = hashes.toSet()
        val changed = synchronized(lock) {
            val previous = peerBlobs.put(peerId, set)
            previous != set
        }
        if (changed) bump()
    }

    /**
     * Claims the next blob to pull from the peer [peerId]. A blob being pulled may take the
     * largest size, so no pull starts while the blobs that wait for their records and the
     * ones being pulled could take more than [maxReceivedBytes]: the pulls wait for a sync
     * pass to write records instead, and records that are never written hold no more.
     * The blob [resume] comes first, also while it is passed over: what arrived of it waits.
     *
     * A peer names at most [BlobExchange.MAX_ADVERTISED] blobs. One that named that many may
     * hold others, so it is also asked for the ones it did not name, after those it did. If
     * it does not hold one, it answers BLOB_NOPE, and is passed over for that blob for a while.
     */
    override fun claimNext(peerId: String, resume: String?): String? {
        // Read under the lock: a blob that arrived meanwhile counts as received, as
        // [onReceived] keeps it before it drops its claim.
        return synchronized(lock) {
            val arrived = received.hashes()
            val waiting = received.size()
            val held = peerBlobs[peerId].orEmpty()
            val full = held.size >= BlobExchange.MAX_ADVERTISED
            val now = clock()
            val candidates = needed
                .takeIf { waiting + (claims.size + 1L) * MAX_BLOB_SIZE <= maxReceivedBytes }
                .orEmpty()
                .filter { hash -> (full || hash in held) && hash !in claims && hash !in arrived }
            val next = candidates.firstOrNull { it == resume }
                ?: candidates
                    .sortedWith(compareBy({ it !in held }, { it }))
                    .firstOrNull { hash -> passedOver[peerId to hash]?.let { now >= it } ?: true }
            next?.also { hash -> claims[hash] = peerId }
        }
    }

    override fun wanted(): Set<String> {
        val arrived = received.hashes()
        return synchronized(lock) { needed } - arrived
    }

    override suspend fun onReceived(
        peerId: String,
        hash: String,
        bytes: ByteArray,
    ) {
        val accepted = categories.blobs.any { it.accepts(bytes) }
        if (accepted) received.put(hash, bytes)
        synchronized(lock) {
            if (claims[hash] == peerId) claims.remove(hash)
            if (!accepted) passedOver[peerId to hash] = clock() + retryDelayMs
        }
        bump()
        // The next pass writes the records of the blob.
        if (accepted) queueSync().bind()
    }

    override fun onFailed(peerId: String, hash: String) {
        synchronized(lock) {
            if (claims[hash] == peerId) claims.remove(hash)
            passedOver[peerId to hash] = clock() + retryDelayMs
        }
        bump()
    }

    override fun onSessionEnded(peerId: String) {
        synchronized(lock) {
            peerBlobs.remove(peerId)
            claims.values.removeAll { it == peerId }
        }
        bump()
    }

    private fun bump() {
        versionSink.update { it + 1 }
    }
}
