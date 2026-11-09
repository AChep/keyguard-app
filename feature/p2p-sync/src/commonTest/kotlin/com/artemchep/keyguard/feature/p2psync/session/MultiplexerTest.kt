package com.artemchep.keyguard.feature.p2psync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiplexerTest {
    private var nextId = 0

    private fun frame(type: FrameType) = Frame(type.code, FrameCodec.FLAG_FIN, nextId++, ByteArray(0))

    private fun Multiplexer.drain(): List<FrameType?> = generateSequence { next() }.map { it.type }.toList()

    @Test
    fun `control frames go before sync frames`() {
        val multiplexer = Multiplexer()
        repeat(3) { multiplexer.enqueue(frame(FrameType.ROSTER)) }
        repeat(3) { multiplexer.enqueue(frame(FrameType.ENVELOPE)) }
        multiplexer.enqueue(frame(FrameType.HEARTBEAT))
        multiplexer.enqueue(frame(FrameType.ENVELOPE_ACK))
        assertEquals(
            listOf(FrameType.HEARTBEAT, FrameType.ENVELOPE_ACK) +
                List(3) { FrameType.ROSTER } + List(3) { FrameType.ENVELOPE },
            multiplexer.drain(),
        )
    }

    @Test
    fun `control frames bypass the queue`() {
        val multiplexer = Multiplexer()
        repeat(3) { multiplexer.enqueue(frame(FrameType.ROSTER)) }
        multiplexer.enqueue(frame(FrameType.ENVELOPE))
        multiplexer.enqueue(frame(FrameType.HEARTBEAT))
        assertEquals(FrameType.HEARTBEAT, multiplexer.next()?.type)
        multiplexer.enqueue(frame(FrameType.ENVELOPE_ACK))
        assertEquals(FrameType.ENVELOPE_ACK, multiplexer.next()?.type)
        assertEquals(FrameType.ROSTER, multiplexer.next()?.type)
    }

    @Test
    fun `frames of one channel keep their order`() {
        val multiplexer = Multiplexer()
        val frames = List(5) { frame(FrameType.ENVELOPE) }
        multiplexer.enqueueAll(frames)
        assertEquals(frames, generateSequence { multiplexer.next() }.toList())
        assertTrue(multiplexer.isEmpty)
        assertNull(multiplexer.next())
    }

    @Test
    fun `frames of an unknown type go with control frames`() {
        val multiplexer = Multiplexer()
        multiplexer.enqueue(frame(FrameType.ENVELOPE))
        val unknown = Frame(0x7f, FrameCodec.FLAG_FIN, nextId++, ByteArray(0))
        multiplexer.enqueue(unknown)
        assertEquals(unknown, multiplexer.next())
        assertEquals(FrameType.ENVELOPE, multiplexer.next()?.type)
    }

    @Test
    fun `sync and blob frames share the stream two to one`() {
        val multiplexer = Multiplexer()
        repeat(6) { multiplexer.enqueue(frame(FrameType.BLOB_DATA)) }
        repeat(6) { multiplexer.enqueue(frame(FrameType.ENVELOPE)) }
        val order = multiplexer.drain()
        assertEquals(
            listOf("S", "S", "B", "S", "S", "B", "S", "S", "B", "B", "B", "B"),
            order.map { if (it == FrameType.ENVELOPE) "S" else "B" },
        )
    }

    @Test
    fun `a lone blob channel gets the whole stream`() {
        val multiplexer = Multiplexer()
        repeat(4) { multiplexer.enqueue(frame(FrameType.BLOB_DATA)) }
        assertEquals(List(4) { FrameType.BLOB_DATA }, multiplexer.drain())
    }

    @Test
    fun `the queued bytes follow the frames`() {
        val multiplexer = Multiplexer()
        multiplexer.enqueue(Frame(FrameType.ENVELOPE.code, FrameCodec.FLAG_FIN, 1, ByteArray(10)))
        multiplexer.enqueue(Frame(FrameType.HEARTBEAT.code, FrameCodec.FLAG_FIN, 2, ByteArray(3)))
        assertEquals(13L, multiplexer.queuedBytes)
        multiplexer.next()
        assertEquals(10L, multiplexer.queuedBytes)
        multiplexer.next()
        assertEquals(0L, multiplexer.queuedBytes)
    }
}
