package com.artemchep.keyguard.feature.p2psync.testing

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import com.artemchep.keyguard.feature.p2psync.transport.Decompressed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** How one direction of an [InMemoryByteStream] misbehaves. */
class StreamKnobs(
    /** Every message arrives this much later. */
    val delayMs: Long = 0L,
    /** Messages for which this returns `true` are lost. */
    val drop: (ByteArray) -> Boolean = { false },
    /** The chance that a message is held back and delivered after the next one. */
    val reorderProbability: Double = 0.0,
    val random: Random = Random(0),
)

/** One end of an in-memory message stream; see [pair]. */
class InMemoryByteStream private constructor(
    private val scope: CoroutineScope,
    private val knobs: StreamKnobs,
) : ByteStream {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private lateinit var peer: InMemoryByteStream
    private var held: ByteArray? = null
    private var closed = false

    /** Every message written to this end, dropped or not. */
    val written = mutableListOf<ByteArray>()

    override suspend fun read(): ByteArray? = inbox.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        if (closed) return
        written += bytes
        if (knobs.drop(bytes)) return
        val holdBack = knobs.reorderProbability > 0.0 && knobs.random.nextDouble() < knobs.reorderProbability
        val previous = held
        when {
            previous == null && holdBack -> held = bytes
            previous != null -> {
                held = null
                deliver(bytes)
                deliver(previous)
            }
            else -> deliver(bytes)
        }
    }

    override suspend fun awaitWritable() = Unit

    override suspend fun close() {
        closed = true
        inbox.close()
        peer.inbox.close()
    }

    private fun deliver(bytes: ByteArray) {
        if (knobs.delayMs > 0) {
            scope.launch {
                delay(knobs.delayMs)
                peer.inbox.trySend(bytes)
            }
        } else {
            peer.inbox.trySend(bytes)
        }
    }

    companion object {
        /** Returns two connected ends; [aToB] shapes what A writes, [bToA] what B writes. */
        fun pair(
            scope: CoroutineScope,
            aToB: StreamKnobs = StreamKnobs(),
            bToA: StreamKnobs = StreamKnobs(),
        ): Pair<InMemoryByteStream, InMemoryByteStream> {
            val a = InMemoryByteStream(scope, aToB)
            val b = InMemoryByteStream(scope, bToA)
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

/** TEST ONLY: a compressor that does not compress. */
object IdentityCompressor : Compressor {
    override fun compress(bytes: ByteArray): ByteArray = bytes

    override fun decompress(bytes: ByteArray, maxSize: Int): Decompressed =
        if (bytes.size <= maxSize) Decompressed.Bytes(bytes) else Decompressed.TooLarge
}
