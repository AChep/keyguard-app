package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.session.CloseReason
import com.artemchep.keyguard.feature.p2psync.session.Frame
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import com.artemchep.keyguard.feature.p2psync.session.Multiplexer
import com.artemchep.keyguard.feature.p2psync.session.SessionEffect
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionStateMachine
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/** Where a sync session with one peer is. */
data class KeyguardPeerProgress(
    val established: Boolean = false,
    /** The hash of the local envelope the session holds. */
    val localHash: String? = null,
    /** The peer acknowledged, or already had, [localHash]. */
    val upToDate: Boolean = false,
    /** A roster or envelope of the peer is partly received. */
    val receiving: Boolean = false,
    /**
     * Nothing more can cross: both sides hold each other's latest envelope, and nothing of
     * the peer is [receiving], or an envelope too large stands in the way.
     */
    val exchangeDone: Boolean = false,
    /** The size of the local envelope, if it is too large to send. */
    val localTooLarge: Int? = null,
    /** The peer's latest envelope is too large to take. */
    val peerTooLarge: Boolean = false,
)

/** What a peer session hands to its session manager. Called under the session's lock: must not block. */
interface KeyguardPeerListener {
    /**
     * Merge the [envelope] the peer sent, whose content hash is [hash], into the local
     * state; the roster that came with it was handed over first, see [onRoster].
     */
    fun onEnvelope(envelope: Envelope, hash: String)

    /** Merge the roster of the session, which adopted the one the peer sent, into the local one. */
    fun onRoster(roster: Roster)
}

/**
 * Drives one [SyncSessionStateMachine] over an authenticated [stream]: frames in and out
 * through a [Multiplexer], a tick every second, and every new local envelope. The
 * blobs go through [blobs]; without it, a request for one is answered with BLOB_NOPE.
 */
@Suppress("TooManyFunctions") // The protocol steps and transport I/O of one peer session.
internal class KeyguardPeerSession(
    /** The id of the peer device. */
    val peerId: String,
    private val stream: ByteStream,
    private val machine: SyncSessionStateMachine,
    private val listener: KeyguardPeerListener,
    /** A monotonic clock in milliseconds. */
    private val clock: () -> Long,
    private val blobs: KeyguardPeerBlobs? = null,
) {
    companion object {
        /** How often a state machine, of a session or an enrollment, gets a tick. */
        const val TICK_MS: Long = 1_000L

        /** How long the last frames, such as BYE, may take to leave. */
        private const val FLUSH_TIMEOUT_MS: Long = 2_000L

        /**
         * How long a written BYE gets to reach the peer before the stream closes: closing a
         * WebRTC connection drops what it still buffers, and a peer that never hears the BYE
         * keeps the session until it times out.
         */
        const val BYE_LINGER_MS: Long = 500L

        /**
         * The most bytes the session queues for a peer, three of the largest messages, see
         * [FrameCodec.MAX_MESSAGE_SIZE]: a peer that stops reading, or asks for more than it
         * reads, ends the session instead.
         */
        const val MAX_QUEUED_BYTES: Long = 3L * FrameCodec.MAX_MESSAGE_SIZE
    }

    private val mutex = Mutex()

    private val multiplexer = Multiplexer()

    private val outgoing = Channel<Unit>(Channel.CONFLATED)

    /** Why the session ended; `null` if the stream ended without a BYE. */
    private val ended = CompletableDeferred<CloseReason?>()

    private val progressSink = MutableStateFlow(KeyguardPeerProgress())

    private data class Output(
        val rosters: Long = 0L,
        val finished: Boolean = false,
    )

    private val output = MutableStateFlow(Output())

    /** Complete roster messages queued under [mutex], including ones not yet written. */
    private var queuedRosters = 0L

    private var queuedRosterHash: String? = null

    /** Why the state machine closed, under [mutex]. */
    @Volatile
    private var closeReason: CloseReason? = null

    /** The latest local envelope; touched under [mutex]. */
    private var local: LocalEnvelope? = null

    /**
     * The BYE frame, held back until every other frame left: the multiplexer would send
     * it first, ahead of the roster that tells a revoked peer why.
     */
    private var bye: Frame? = null

    /** `true` once the BYE frame was written, after every other frame. */
    @Volatile
    var byeSent: Boolean = false
        private set

    val progress: StateFlow<KeyguardPeerProgress> = progressSink.asStateFlow()

    /** `true` once both sides accepted each other's HELLO, even if the session ended since. */
    @Volatile
    var wasEstablished: Boolean = false
        private set

    /**
     * Runs the session from the [initial] local envelope, taking every later one from
     * [updates], until either side ends it. Returns why it ended, or `null` if the stream
     * closed without a reason. Closes the stream.
     */
    suspend fun run(
        initial: LocalEnvelope,
        updates: Flow<LocalEnvelope>,
    ): CloseReason? = try {
        coroutineScope {
            step {
                local = initial
                machine.start(clock(), initial)
            }
            val writer = launch { write() }
            val reader = launch { read() }
            launch { tick() }
            if (blobs != null) {
                launch {
                    blobs.run(
                        established = progressSink.map { it.established },
                        step = { block -> step { machine.blobs.block() } },
                    )
                }
            }
            launch {
                updates.collect { envelope ->
                    step {
                        if (!machine.isClosed) local = envelope
                        machine.onLocalEnvelopeChanged(envelope)
                    }
                }
            }
            val reason = ended.await()
            reader.cancel()
            // Let the last frames, such as BYE, leave before the stream closes.
            outgoing.close()
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { writer.join() }
            if (byeSent) delay(BYE_LINGER_MS)
            coroutineContext.cancelChildren()
            reason
        }
    } finally {
        try {
            stream.close()
        } finally {
            output.update { it.copy(finished = true) }
        }
    }

    /**
     * Publishes [envelope] directly, so a roster-only edit needs no flow emission to be
     * processed first. Waits for the roster's writes, then the envelope's receipt or the
     * revocation's graceful close. A closed or failed stream is not successful publication.
     */
    suspend fun publish(envelope: LocalEnvelope): Boolean {
        val target = mutex.withLock {
            if (machine.isClosed && local?.roster != envelope.roster) {
                null
            } else {
                local = envelope
                handle(machine.onLocalEnvelopeChanged(envelope))
                updateProgress()
                // An oversized roster must not look published merely because the
                // unchanged data envelope was acknowledged already.
                queuedRosters.takeIf {
                    queuedRosterHash == machine.rosterHash || machine.peerRosterHash == machine.rosterHash
                }
            }
        } ?: return false
        return combine(output, progress) { written, current ->
            when {
                written.finished -> written.rosters >= target && byeSent &&
                    closeReason in setOf(CloseReason.LOCAL_REVOKED, CloseReason.PEER_REVOKED)
                written.rosters >= target && current.established && current.localHash == envelope.contentHash &&
                    (current.upToDate || current.localTooLarge != null) -> current.localTooLarge == null
                else -> null
            }
        }.first { it != null } == true
    }

    /**
     * Asks the peer to send its roster and envelope again, within the budget of the session,
     * see [SyncSessionStateMachine.requestPeerResend].
     */
    suspend fun requestResend() {
        step { machine.requestPeerResend() }
    }

    /**
     * Ends the session with [reason], telling the peer, and lets the last frames leave:
     * [run] returns soon after. Ends a session that did not start yet at once.
     */
    suspend fun close(reason: CloseReason = CloseReason.LOCAL_CLOSED) {
        step { machine.close(reason) }
        ended.complete(reason)
    }

    private suspend fun step(block: () -> List<SessionEffect>) {
        mutex.withLock {
            handle(block())
            updateProgress()
        }
    }

    private fun updateProgress() {
        if (machine.isEstablished) wasEstablished = true
        progressSink.value = KeyguardPeerProgress(
            established = machine.isEstablished,
            localHash = machine.localHash,
            upToDate = machine.isPeerUpToDate,
            receiving = machine.isReceiving,
            exchangeDone = machine.isExchangeDone,
            localTooLarge = machine.localEnvelopeTooLarge,
            peerTooLarge = machine.isPeerEnvelopeTooLarge,
        )
    }

    private fun handle(effects: List<SessionEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is SessionEffect.SendFrame -> send(effect.frame)

                is SessionEffect.ApplyEnvelope -> listener.onEnvelope(effect.envelope, effect.hash)
                is SessionEffect.ApplyRoster -> listener.onRoster(effect.roster)
                SessionEffect.RequestResend -> local?.let { handle(machine.onLocalEnvelopeChanged(it)) }
                is SessionEffect.Close -> {
                    closeReason = effect.reason
                    ended.complete(effect.reason)
                }

                // The blobs are read and written away from the lock.
                is SessionEffect.ReadBlob -> if (blobs != null) {
                    blobs.post(effect)
                } else {
                    handle(machine.blobs.onBlobMissing(effect.hash))
                }

                is SessionEffect.PublishBlob,
                is SessionEffect.BlobUnavailable,
                is SessionEffect.BlobFailed,
                is SessionEffect.PeerBlobs,
                -> blobs?.post(effect)
            }
        }
    }

    /** Queues [frame], holding the BYE back until every other frame left. */
    private fun send(frame: Frame) {
        if (frame.type == FrameType.BYE) {
            bye = frame
            return
        }
        multiplexer.enqueue(frame)
        if (frame.type == FrameType.ROSTER && frame.isFinal) {
            queuedRosters++
            queuedRosterHash = machine.rosterHash
        }
        outgoing.trySend(Unit)
        // A peer that stops reading must not make this device hold its frames forever.
        if (multiplexer.queuedBytes > MAX_QUEUED_BYTES) ended.complete(CloseReason.TIMEOUT)
    }

    private suspend fun read() {
        catchingTransport {
            while (true) {
                val bytes = stream.read() ?: break
                step { machine.onFrame(bytes) }
            }
        }
        ended.complete(null)
    }

    private suspend fun write() {
        val written = catchingTransport {
            for (signal in outgoing) drain()
            drain()
            mutex.withLock { bye.also { bye = null } }?.let { frame ->
                stream.write(FrameCodec.encode(frame))
                byeSent = true
            }
        }
        if (!written) ended.complete(null)
    }

    private suspend fun drain() {
        while (true) {
            val frame = mutex.withLock { multiplexer.next() } ?: return
            stream.awaitWritable()
            stream.write(FrameCodec.encode(frame))
            if (frame.type == FrameType.ROSTER && frame.isFinal) {
                output.update { it.copy(rosters = it.rosters + 1L) }
            }
        }
    }

    private suspend fun tick() {
        while (true) {
            delay(TICK_MS)
            step { machine.onTick(clock()) }
        }
    }

    /** Runs [block]; a transport failure is the end of the stream. Returns `false` on one. */
    private suspend fun catchingTransport(block: suspend () -> Unit): Boolean = runCatchingUnlessCancelled {
        block()
    }.isSuccess
}
