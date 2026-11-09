package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import kotlinx.coroutines.channels.Channel

/** TEST ONLY: two connected ends of an ordered message pipe. */
class TestPipe private constructor(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
    private val tamper: (ByteArray) -> ByteArray,
) : ByteStream {
    /** Every message written to this end. */
    val written = mutableListOf<ByteArray>()

    override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        written += bytes
        outgoing.send(tamper(bytes))
    }

    override suspend fun awaitWritable() = Unit

    override suspend fun close() {
        incoming.close()
        outgoing.close()
    }

    companion object {
        /** Returns two ends; [aToB] may change what A writes on its way to B. */
        fun pair(aToB: (ByteArray) -> ByteArray = { it }): Pair<TestPipe, TestPipe> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            return TestPipe(ba, ab, aToB) to TestPipe(ab, ba) { it }
        }
    }
}
