package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.NOISE_HANDSHAKE_TIMEOUT_MS
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A stream and the transport that opened it.
 *
 * @property iceServers the ICE servers the connection was given, if the transport took
 *   some, see [KeyguardRoom.connect][com.artemchep.keyguard.provider.keyguard.session.KeyguardRoom.connect].
 */
class SelectedStream(
    val kind: TransportKind,
    val stream: ByteStream,
    val iceServers: IceServersGrant? = null,
)

/**
 * Races the [transports] of a session for each connection, in the order the application
 * roots list them: WebRTC first, relay forwarding last. Only the transports both devices
 * support take part, so a device never waits on WebRTC for a peer that lacks it.
 *
 * The initiator starts the first transport, and each next one [headStartMs] after the one
 * before it, or once all before it failed. The first stream that opens wins; the attempts
 * still running stop, and a stream that opens late closes. The initiator then writes the
 * first message of its handshake on the winner.
 *
 * The responder waits on every transport at once, and takes the stream the first message
 * arrives on: the one the initiator picked. It closes the others.
 */
class TransportSelector(
    private val transports: List<Transport>,
    private val headStartMs: Long = HEAD_START_MS,
    /** How long the responder waits for the first message on a stream that opened. */
    private val firstMessageTimeoutMs: Long = NOISE_HANDSHAKE_TIMEOUT_MS,
) {
    companion object {
        /** 2.5 seconds: a direct connection on the same network opens well within it. */
        const val HEAD_START_MS: Long = 2_500L
    }

    init {
        require(transports.isNotEmpty()) { "No transports" }
    }

    /** The transports this device supports. */
    val kinds: Set<TransportKind> = transports.mapTo(linkedSetOf()) { it.kind }

    /**
     * Returns the transports to race for a peer that supports [peerKinds], in order; all of
     * them if the peer did not say.
     */
    fun candidates(peerKinds: Set<TransportKind>?): List<Transport> =
        if (peerKinds == null) transports else transports.filter { it.kind in peerKinds }

    /**
     * Connects to [peerId], which supports [peerKinds], over the first transport that
     * opens. The stream of a responder returns the first message of the initiator again.
     *
     * @throws TransportException when none opens, caused by the last failure.
     */
    suspend fun connect(
        peerId: String,
        role: ConnectRole,
        peerKinds: Set<TransportKind>? = null,
    ): SelectedStream {
        val candidates = candidates(peerKinds)
        if (candidates.isEmpty()) throw TransportException("The device supports none of the transports of this one")
        return Race(peerId, candidates).run(role)
    }

    /** One connection attempt over the [candidates]. */
    private inner class Race(
        private val peerId: String,
        private val candidates: List<Transport>,
    ) {
        private val winner = CompletableDeferred<SelectedStream>()

        /** The indexes of the candidates that failed. */
        private val failed = MutableStateFlow(emptySet<Int>())

        suspend fun run(role: ConnectRole): SelectedStream {
            var returned = false
            try {
                return race(role).also { returned = true }
            } finally {
                // The attempts are over: a stream that won, but never reached the caller, as
                // it was cancelled meanwhile, is closed.
                if (!returned && winner.isCompleted) {
                    withContext(NonCancellable) {
                        runCatchingUnlessCancelled { winner.await().stream.close() }
                    }
                }
            }
        }

        private suspend fun race(role: ConnectRole): SelectedStream = coroutineScope {
            val attempts = candidates.mapIndexed { index, transport ->
                launch {
                    if (role == ConnectRole.INITIATOR) awaitTurn(index)
                    val result = runCatchingUnlessCancelled {
                        when (role) {
                            ConnectRole.INITIATOR -> initiate(transport)
                            ConnectRole.RESPONDER -> respond(transport)
                        }
                    }
                    result.exceptionOrNull()?.let { onFailure(index, it) }
                }
            }
            try {
                winner.await()
            } finally {
                attempts.forEach { it.cancel() }
            }
        }

        /** Waits for the head start of the candidates before [index], or for all of them to fail. */
        private suspend fun awaitTurn(index: Int) {
            if (index == 0) return
            withTimeoutOrNull(headStartMs * index) {
                failed.first { set -> (0 until index).all { it in set } }
            }
        }

        private suspend fun initiate(transport: Transport) {
            val stream = transport.connect(peerId, ConnectRole.INITIATOR)
            if (!winner.complete(SelectedStream(transport.kind, stream))) {
                withContext(NonCancellable) { stream.close() }
            }
        }

        private suspend fun respond(transport: Transport) {
            val stream = transport.connect(peerId, ConnectRole.RESPONDER)
            var kept = false
            try {
                val first = withTimeoutOrNull(firstMessageTimeoutMs) { stream.read() }
                    ?: throw TransportException("The device sent nothing on the stream")
                kept = winner.complete(SelectedStream(transport.kind, PrefetchedStream(stream, first)))
            } finally {
                if (!kept) withContext(NonCancellable) { stream.close() }
            }
        }

        private fun onFailure(index: Int, cause: Throwable) {
            val all = failed.updateAndGet { it + index }
            if (all.size == candidates.size) {
                winner.completeExceptionally(TransportException("No transport reached the device", cause))
            }
        }
    }
}

/**
 * A stream whose first [read] returns [first], a message already read from [inner]. Read
 * by one reader at a time.
 */
internal class PrefetchedStream(
    private val inner: ByteStream,
    first: ByteArray,
) : ByteStream {
    private var first: ByteArray? = first

    override suspend fun read(): ByteArray? = first
        ?.also { first = null }
        ?: inner.read()

    override suspend fun write(bytes: ByteArray) = inner.write(bytes)

    override suspend fun awaitWritable() = inner.awaitWritable()

    override suspend fun close() = inner.close()
}
