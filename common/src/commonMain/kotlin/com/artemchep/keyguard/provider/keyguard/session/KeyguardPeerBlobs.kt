package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.session.SessionEffect
import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a call of the session's [BlobExchange] under the session lock and carries out its effects. */
internal typealias KeyguardBlobExchangeStep = suspend (BlobExchange.() -> List<SessionEffect>) -> Unit

/**
 * The blobs of one peer session. Tells the peer which blobs this device holds, serves the
 * windows the peer asks for, and pulls the blobs the [blobs] of the group hand this
 * session, one at a time. The blob effects of the session are carried out here, in order,
 * away from the session lock.
 *
 * A blob is small enough to stay in memory: a pulled one is kept by the session until it
 * is complete, and the one being served is kept between its windows.
 *
 * What the peer sends waits in slots that hold its newest one only: the window it asked for
 * last, as an honest peer asks for one at a time, and the blobs it said it holds last. So a
 * peer that sends faster than the database answers overwrites what waits, and what this
 * device holds for it stays the same. The results of this device's own transfer wait in
 * order; it pulls one blob at a time, so only a few ever do.
 *
 * @param changes emits whenever the local records of the group may have changed.
 */
internal class KeyguardPeerBlobs(
    private val peerId: String,
    private val blobs: P2pGroupBlobs,
    private val changes: Flow<Unit>,
) {
    companion object {
        /** How often an idle session looks for a blob to pull, so a passed-over blob is tried again. */
        private const val RECHECK_MS = 5_000L

        /** The most results of this device's own transfers that wait; it pulls one blob at a time. */
        private const val MAX_RESULTS = 8
    }

    private class ServedBlob(
        val hash: String,
        val bytes: ByteArray,
    )

    /** Wakes [run] once for any number of changes below. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** The window the peer asked for last. */
    private val request = MutableStateFlow<SessionEffect.ReadBlob?>(null)

    /** The blobs the peer said it holds, last. */
    private val peerBlobs = MutableStateFlow<SessionEffect.PeerBlobs?>(null)

    /** `true` to tell the peer again which blobs are here. */
    private val refresh = MutableStateFlow(false)

    /** How this device's own transfers ended, in order. */
    private val results = Channel<SessionEffect>(MAX_RESULTS)

    /** The blob being pulled, touched only by [run]. */
    private var current: String? = null

    /** The blob being served, touched only by [run]. */
    private var served: ServedBlob? = null

    /** The blobs the peer was told about, touched only by [run]. */
    private var advertised: Set<String>? = null

    /**
     * Queues a blob [effect]; never blocks, so it may be called under the session lock. The
     * window or the blobs of the peer replace the ones that wait.
     */
    fun post(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.ReadBlob -> request.value = effect
            is SessionEffect.PeerBlobs -> peerBlobs.value = effect
            // Only a broken session gets here: it ends, and the next one starts afresh.
            else -> check(results.trySend(effect).isSuccess) { "Too many blob results wait" }
        }
        signal.trySend(Unit)
    }

    /** Looks for a blob to pull; [refresh] also tells the peer again which blobs are here. */
    private fun wake(refresh: Boolean) {
        if (refresh) this.refresh.value = true
        signal.trySend(Unit)
    }

    /**
     * Runs until cancelled: once the session is [established], tells the peer which blobs
     * are here, then pulls and serves blobs through [step].
     */
    suspend fun run(
        established: Flow<Boolean>,
        step: KeyguardBlobExchangeStep,
    ) {
        try {
            established.first { it }
            advertiseHeld(step)
            coroutineScope {
                launch {
                    merge(blobs.version.drop(1).map { }, changes)
                        .collect { wake(refresh = true) }
                }
                launch {
                    while (true) {
                        delay(RECHECK_MS)
                        wake(refresh = false)
                    }
                }
                wake(refresh = false)
                while (true) {
                    signal.receive()
                    drain(step)
                }
            }
        } finally {
            withContext(NonCancellable) {
                current = null
                served = null
                blobs.onSessionEnded(peerId)
            }
        }
    }

    /** Carries out what waits, in order, then looks for a blob to pull. */
    private suspend fun drain(step: KeyguardBlobExchangeStep) {
        while (true) {
            val result = results.tryReceive().getOrNull() ?: break
            attempt { handle(result) }
        }
        peerBlobs.getAndUpdate { null }?.let { effect -> attempt { blobs.onPeerBlobs(peerId, effect.hashes) } }
        request.getAndUpdate { null }?.let { effect -> attempt { serve(effect, step) } }
        if (refresh.getAndUpdate { false }) attempt { advertiseHeld(step) }
        if (current == null) attempt { pullNext(step) }
    }

    /** Tells the peer which blobs are here, if that changed since it was last told. */
    private suspend fun advertiseHeld(step: KeyguardBlobExchangeStep) {
        val held = attempt { blobs.held() }
            ?: return
        if (held == advertised) return
        advertised = held
        step { advertise(held) }
    }

    private suspend fun pullNext(step: KeyguardBlobExchangeStep) {
        val hash = blobs.claimNext(peerId)
            ?: return
        current = hash
        step { want(hash) }
    }

    /** Serves the window the peer asked for. */
    private suspend fun serve(
        effect: SessionEffect.ReadBlob,
        step: KeyguardBlobExchangeStep,
    ) {
        // Whatever went wrong, the peer hears that the blob is not here.
        val offset = effect.offset
        val blob = attempt { read(effect.hash) }
            ?.takeIf { offset in 0L..it.size.toLong() }
        val end = blob?.let { minOf(it.size.toLong(), offset + effect.maxLength).toInt() }
        // Only a blob the peer is still pulling stays here, see [read]: the last window lets it go.
        served = if (blob != null && end != null && end < blob.size) ServedBlob(effect.hash, blob) else null
        step {
            if (blob == null || end == null) {
                onBlobMissing(effect.hash)
            } else {
                val window = blob.copyOfRange(offset.toInt(), end)
                onBlobRead(effect.hash, offset, blob.size.toLong(), window)
            }
        }
    }

    /** Carries out how a transfer of this device ended. */
    private suspend fun handle(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.PublishBlob -> {
                finish(effect.hash)
                blobs.onReceived(peerId, effect.hash, effect.bytes)
            }

            is SessionEffect.BlobUnavailable -> {
                finish(effect.hash)
                blobs.onFailed(peerId, effect.hash)
            }

            is SessionEffect.BlobFailed -> {
                finish(effect.hash)
                blobs.onFailed(peerId, effect.hash)
            }

            else -> Unit
        }
    }

    private fun finish(hash: String) {
        if (current == hash) current = null
    }

    /**
     * Reads the blob [hash]. A transfer asks for its windows in order, so the blob stays here
     * between them; this device must still hand it over for each window, as the user may
     * have stopped syncing its category since.
     */
    private suspend fun read(hash: String): ByteArray? {
        val cached = served?.takeIf { it.hash == hash }
        if (cached != null && hash in blobs.held()) return cached.bytes
        served = null
        return blobs.read(hash)
    }

    /** Runs [block]; a failure of one blob never ends the session, the blob is tried again later. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = runCatchingUnlessCancelled {
        block()
    }.getOrNull()
}
