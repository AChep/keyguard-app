package com.artemchep.keyguard.feature.p2psync.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameCodecTest {
    private fun frame(type: FrameType = FrameType.ENVELOPE, flags: Int = FrameCodec.FLAG_FIN, payload: ByteArray) =
        Frame(typeCode = type.code, flags = flags, msgId = 7, payload = payload)

    private fun reassemble(frames: List<Frame>, reassembler: Reassembler = Reassembler()): ReassemblyResult =
        frames.map { reassembler.accept(FrameCodec.decode(FrameCodec.encode(it))) }.last()

    @Test
    fun `frames round trip`() {
        val payloads = listOf(
            ByteArray(0),
            byteArrayOf(0),
            "héllo 😀 мир".encodeToByteArray(),
            ByteArray(FrameCodec.MAX_PAYLOAD_SIZE) { it.toByte() },
        )
        payloads.forEach { payload ->
            val original = frame(payload = payload)
            val bytes = FrameCodec.encode(original)
            assertEquals(FrameCodec.HEADER_SIZE + payload.size, bytes.size)
            assertEquals(original, FrameCodec.decode(bytes))
        }
    }

    @Test
    fun `the header is type, flags and message id, big-endian`() {
        assertEquals(6, FrameCodec.HEADER_SIZE)
        val bytes = FrameCodec.encode(
            Frame(
                typeCode = FrameType.ROSTER.code,
                flags = FrameCodec.FLAG_MORE,
                msgId = -2,
                payload = byteArrayOf(9),
            ),
        )
        assertContentEquals(byteArrayOf(0x06, 2, -1, -1, -1, -2, 9), bytes)
        val decoded = FrameCodec.decode(bytes)
        assertEquals(-2, decoded.msgId)
        assertEquals(FrameType.ROSTER, decoded.type)
    }

    @Test
    fun `invalid frames are refused`() {
        assertEquals(FrameError.TRUNCATED, assertFailsWith<FrameException> { FrameCodec.decode(ByteArray(5)) }.error)
        val oversized = ByteArray(FrameCodec.MAX_FRAME_SIZE + 1).also { it[1] = 1 }
        assertEquals(FrameError.OVERSIZED, assertFailsWith<FrameException> { FrameCodec.decode(oversized) }.error)
        listOf(0, 3, 4).forEach { flags ->
            val bytes = ByteArray(FrameCodec.HEADER_SIZE).also { it[1] = flags.toByte() }
            assertEquals(FrameError.INVALID_FLAGS, assertFailsWith<FrameException> { FrameCodec.decode(bytes) }.error)
        }
    }

    @Test
    fun `unknown types decode so that they can be skipped`() {
        val decoded = FrameCodec.decode(FrameCodec.encode(Frame(0x7f, FrameCodec.FLAG_FIN, 1, ByteArray(0))))
        assertEquals(0x7f, decoded.typeCode)
        assertEquals(null, decoded.type)
    }

    @Test
    fun `messages split into frames of at most 32 KiB and reassemble`() {
        val random = Random(3)
        listOf(0, 1, FrameCodec.MAX_PAYLOAD_SIZE, FrameCodec.MAX_PAYLOAD_SIZE + 1, 200_000).forEach { size ->
            val payload = random.nextBytes(size)
            val frames = FrameCodec.split(FrameType.ENVELOPE, msgId = 1, payload = payload)
            assertTrue(frames.all { FrameCodec.encode(it).size <= FrameCodec.MAX_FRAME_SIZE })
            assertTrue(frames.dropLast(1).all { it.flags == FrameCodec.FLAG_MORE })
            assertEquals(FrameCodec.FLAG_FIN, frames.last().flags)
            val complete = assertIs<ReassemblyResult.Complete>(reassemble(frames))
            assertContentEquals(payload, complete.message.payload)
            assertEquals(FrameType.ENVELOPE, complete.message.type)
        }
    }

    @Test
    fun `messages may interleave`() {
        val a = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(100) { 1 }, maxPayload = 30)
        val b = FrameCodec.split(FrameType.ROSTER, 2, ByteArray(70) { 2 }, maxPayload = 30)
        val reassembler = Reassembler()
        val interleaved = a.zip(b).flatMap { listOf(it.first, it.second) } + a.drop(b.size)
        // Through the wire and back.
        val results = interleaved.map { reassembler.accept(FrameCodec.decode(FrameCodec.encode(it))) }
        val messages = results.filterIsInstance<ReassemblyResult.Complete>().map { it.message }
        assertEquals(listOf(2, 1), messages.map { it.msgId })
        assertContentEquals(ByteArray(70) { 2 }, messages.first().payload)
        assertContentEquals(ByteArray(100) { 1 }, messages.last().payload)
    }

    @Test
    fun `a frame of a message id that is not in flight starts a new message`() {
        val frames = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(30), maxPayload = 10)
        // The middle of a message is taken as the start of one; the stream never drops a
        // frame, so a message that starts so is malformed as a whole.
        val reassembler = Reassembler()
        val results = frames.drop(1).map(reassembler::accept)
        assertTrue(results.dropLast(1).all { it == ReassemblyResult.Pending })
        assertEquals(ReassemblyResult.Failed(FrameError.INVALID_PADDING), results.last())
        // Once a message completes, its id starts the next one.
        assertIs<ReassemblyResult.Complete>(frames.map(reassembler::accept).last())
        val next = FrameCodec.split(FrameType.ROSTER, 1, ByteArray(5) { 7 })
        val result = assertIs<ReassemblyResult.Complete>(reassembler.accept(next.single()))
        assertEquals(FrameType.ROSTER, result.message.type)
    }

    @Test
    fun `a message larger than the limit is refused`() {
        // The limit counts the padded message: 60 bytes pad to 64, 61 to 96, which the last
        // of three frames takes past the limit.
        val frames = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(61), maxPayload = 32)
        val result = reassemble(frames, Reassembler(maxMessageSize = 64))
        assertEquals(ReassemblyResult.Failed(FrameError.MESSAGE_TOO_LARGE), result)
        val exact = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(60), maxPayload = 32)
        assertIs<ReassemblyResult.Complete>(reassemble(exact, Reassembler(maxMessageSize = 64)))
    }

    @Test
    fun `each type takes messages up to its own limit`() {
        val reassembler = Reassembler()
        val max = FrameType.ENVELOPE.maxSize
        val fits = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(max)).map(reassembler::accept).last()
        assertEquals(max, assertIs<ReassemblyResult.Complete>(fits).message.payload.size)
        // One more byte pads to the same bucket, but the type does not take it.
        val over = FrameCodec.split(FrameType.ENVELOPE, 2, ByteArray(max + 1)).map(reassembler::accept).last()
        assertEquals(ReassemblyResult.Failed(FrameError.MESSAGE_TOO_LARGE), over)
        // A small type is refused at its first frame, before it is buffered.
        val heartbeat = FrameCodec.split(FrameType.HEARTBEAT, 3, ByteArray(64 * 1024))
        assertEquals(ReassemblyResult.Failed(FrameError.MESSAGE_TOO_LARGE), reassembler.accept(heartbeat.first()))
        // So is a type this build does not know, past 64 KiB.
        val unknown = Frame(0x7f, FrameCodec.FLAG_MORE, 4, ByteArray(FrameCodec.MAX_PAYLOAD_SIZE))
        val accepted = (0 until 2).map { reassembler.accept(unknown) }
        assertEquals(listOf<ReassemblyResult>(ReassemblyResult.Pending, ReassemblyResult.Pending), accepted)
        assertEquals(ReassemblyResult.Failed(FrameError.MESSAGE_TOO_LARGE), reassembler.accept(unknown))
        // The framing itself carries no more than the largest type takes, padded.
        assertEquals(FrameType.entries.maxOf { MessagePadding.paddedSize(it.maxSize) }, FrameCodec.MAX_MESSAGE_SIZE)
        assertEquals(FrameType.ENROLL_BUNDLE, FrameType.entries.maxBy { it.maxSize })
        assertFailsWith<IllegalArgumentException> {
            FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(FrameCodec.MAX_UNPADDED_MESSAGE_SIZE + 1))
        }
    }

    @Test
    fun `split messages are padded`() {
        val frames = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(1_000), maxPayload = 300)
        assertEquals(MessagePadding.paddedSize(1_000), frames.sumOf { it.payload.size })
        // An empty message is padded too, so it isn't empty on the wire.
        assertEquals(32, FrameCodec.split(FrameType.HEARTBEAT, 1, ByteArray(0)).single().payload.size)
    }

    @Test
    fun `a message with malformed padding is refused`() {
        val unpadded = frame(payload = ByteArray(10) { 1 })
        assertEquals(ReassemblyResult.Failed(FrameError.INVALID_PADDING), Reassembler().accept(unpadded))
    }

    @Test
    fun `a changed type and too many messages in flight are refused`() {
        val frames = FrameCodec.split(FrameType.ENVELOPE, 1, ByteArray(30), maxPayload = 10)
        val retyped = Frame(FrameType.ROSTER.code, frames[1].flags, 1, frames[1].payload)
        assertEquals(ReassemblyResult.Failed(FrameError.TYPE_CHANGED), reassemble(listOf(frames[0], retyped)))
        val reassembler = Reassembler()
        (1..FrameCodec.MAX_IN_FLIGHT).forEach { id ->
            val started = Frame(2, FrameCodec.FLAG_MORE, id, ByteArray(1))
            assertEquals(ReassemblyResult.Pending, reassembler.accept(started))
        }
        assertEquals(
            ReassemblyResult.Failed(FrameError.TOO_MANY_IN_FLIGHT),
            reassembler.accept(Frame(2, FrameCodec.FLAG_MORE, 99, ByteArray(1))),
        )
        // A single-frame message still gets through.
        val single = Frame(5, FrameCodec.FLAG_FIN, 100, MessagePadding.pad(ByteArray(1)))
        assertIs<ReassemblyResult.Complete>(reassembler.accept(single))
    }

    @Test
    fun `a frame that is not the last of its message carries bytes`() {
        val reassembler = Reassembler()
        assertEquals(ReassemblyResult.Pending, reassembler.accept(Frame(2, FrameCodec.FLAG_MORE, 1, ByteArray(1))))
        // A peer repeats empty frames that never move the message on.
        assertEquals(
            ReassemblyResult.Failed(FrameError.EMPTY_FRAME),
            reassembler.accept(Frame(2, FrameCodec.FLAG_MORE, 1, ByteArray(0))),
        )
        assertEquals(
            ReassemblyResult.Failed(FrameError.EMPTY_FRAME),
            Reassembler().accept(Frame(2, FrameCodec.FLAG_MORE, 2, ByteArray(0))),
        )
    }

    @Test
    fun `a message of many small frames reassembles`() {
        val payload = Random(3).nextBytes(5_000)
        val frames = FrameCodec.split(FrameType.ENVELOPE, 1, payload, maxPayload = 7)
        val result = assertIs<ReassemblyResult.Complete>(reassemble(frames))
        assertContentEquals(payload, result.message.payload)
    }
}
