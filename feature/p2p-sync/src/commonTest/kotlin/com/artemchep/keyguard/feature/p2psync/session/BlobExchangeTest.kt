package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlobExchangeTest {
    private val crypto = TestCrypto(5)
    private val random = Random(5)
    private val config = BlobExchangeConfig(windowSize = 1_000)

    /** A puller and a holder of [blobs], with the frames between them moved by [pump]. */
    private inner class Link(
        val blobs: Map<String, ByteArray>,
        val puller: BlobExchange = BlobExchange(crypto, OutgoingMessages(), config),
    ) {
        val holder = BlobExchange(crypto, OutgoingMessages(), config)
        val pulled = mutableListOf<SessionEffect>()
        val wants = mutableListOf<Long>()
        private val toHolder = ArrayDeque<Frame>()
        private val toPuller = ArrayDeque<Frame>()
        private val holderInbox = Reassembler()
        private val pullerInbox = Reassembler()

        fun fromPuller(effects: List<SessionEffect>) {
            effects.forEach { effect ->
                if (effect is SessionEffect.SendFrame) toHolder.addLast(effect.frame) else pulled += effect
            }
        }

        private fun fromHolder(effects: List<SessionEffect>) {
            effects.forEach { effect ->
                when (effect) {
                    is SessionEffect.SendFrame -> toPuller.addLast(effect.frame)
                    is SessionEffect.ReadBlob -> fromHolder(read(effect))
                    else -> Unit
                }
            }
        }

        private fun read(effect: SessionEffect.ReadBlob): List<SessionEffect> {
            wants += effect.offset
            val blob = blobs[effect.hash] ?: return holder.onBlobMissing(effect.hash)
            val end = minOf(blob.size.toLong(), effect.offset + effect.maxLength).toInt()
            val window = blob.copyOfRange(effect.offset.toInt(), end)
            return holder.onBlobRead(effect.hash, effect.offset, blob.size.toLong(), window)
        }

        /** Moves frames until both directions are idle or [limit] messages reached the puller. */
        fun pump(limit: Int = Int.MAX_VALUE) {
            var delivered = 0
            while ((toHolder.isNotEmpty() || toPuller.isNotEmpty()) && delivered < limit) {
                toHolder.removeFirstOrNull()?.let { frame ->
                    (holderInbox.accept(frame) as? ReassemblyResult.Complete)?.message?.let {
                        fromHolder(holder.onMessage(requireNotNull(it.type), it.payload))
                    }
                }
                toPuller.removeFirstOrNull()?.let { frame ->
                    (pullerInbox.accept(frame) as? ReassemblyResult.Complete)?.message?.let {
                        delivered++
                        fromPuller(puller.onMessage(requireNotNull(it.type), it.payload))
                    }
                }
            }
        }

        inline fun <reified T : SessionEffect> pulled(): List<T> = pulled.filterIsInstance<T>()
    }

    private fun blob(size: Int): Pair<String, ByteArray> {
        val bytes = random.nextBytes(size)
        return BlobExchange.hashOf(crypto, bytes) to bytes
    }

    @Test
    fun `a full window pads to exactly 1 MiB`() {
        val window = ByteArray(BlobExchangeConfig.WINDOW_SIZE)
        val data = BlobData(BlobExchange.hashOf(crypto, window), offset = 0, total = Long.MAX_VALUE, bytes = window)
        assertEquals(1024 * 1024, MessagePadding.paddedSize(data.encode().size))
    }

    @Test
    fun `a blob is pulled window by window and published once verified`() {
        val (hash, bytes) = blob(10_500)
        val link = Link(mapOf(hash to bytes))
        link.fromPuller(link.puller.want(hash))
        link.pump()
        val published = link.pulled<SessionEffect.PublishBlob>().single()
        assertEquals(hash, published.hash)
        assertContentEquals(bytes, published.bytes)
        assertEquals((0L until 10_500L step 1_000L).toList(), link.wants)
    }

    @Test
    fun `a want before the start of a blob is answered with BLOB_NOPE`() {
        val holder = BlobExchange(crypto, OutgoingMessages(), config)
        val want = BlobWantPayload.serializer().encodePayload(BlobWantPayload(h = "blob", off = -1))
        val answer = holder.onMessage(FrameType.BLOB_WANT, want)
        assertEquals(listOf(FrameType.BLOB_NOPE), answer.map { (it as SessionEffect.SendFrame).frame.type })
    }

    @Test
    fun `windows that disagree on the size of the blob fail the transfer`() {
        val (hash, bytes) = blob(2_500)
        val puller = BlobExchange(crypto, OutgoingMessages(), config)
        puller.want(hash)
        val first = BlobData(hash, offset = 0, total = 2_500, bytes = bytes.copyOfRange(0, 1_000))
        assertTrue(puller.onMessage(FrameType.BLOB_DATA, first.encode()).single() is SessionEffect.SendFrame)
        val second = BlobData(hash, offset = 1_000, total = 3_000, bytes = bytes.copyOfRange(1_000, 2_000))
        assertEquals(
            listOf(SessionEffect.BlobFailed(hash, BlobFailure.PROTOCOL_ERROR)),
            puller.onMessage(FrameType.BLOB_DATA, second.encode()),
        )
    }

    @Test
    fun `an empty blob is published at once`() {
        val (hash, bytes) = blob(0)
        val link = Link(mapOf(hash to bytes))
        link.fromPuller(link.puller.want(hash))
        link.pump()
        assertContentEquals(bytes, link.pulled<SessionEffect.PublishBlob>().single().bytes)
    }

    @Test
    fun `a blob whose bytes do not match its hash is refused`() {
        val (hash, bytes) = blob(2_500)
        val corrupted = bytes.copyOf().also { it[1_234] = (it[1_234] + 1).toByte() }
        val link = Link(mapOf(hash to corrupted))
        link.fromPuller(link.puller.want(hash))
        link.pump()
        assertEquals(
            listOf(SessionEffect.BlobFailed(hash, BlobFailure.HASH_MISMATCH)),
            link.pulled<SessionEffect.BlobFailed>(),
        )
        assertTrue(link.pulled<SessionEffect.PublishBlob>().isEmpty())
    }

    @Test
    fun `a blob above the size limit is refused`() {
        val (atLimit, atLimitBytes) = blob(2_000)
        val (above, aboveBytes) = blob(2_001)
        val puller = BlobExchange(crypto, OutgoingMessages(), config.copy(maxBlobSize = 2_000))
        val link = Link(mapOf(atLimit to atLimitBytes, above to aboveBytes), puller)
        link.fromPuller(link.puller.want(above))
        link.pump()
        link.fromPuller(link.puller.want(atLimit))
        link.pump()
        assertEquals(
            listOf(SessionEffect.BlobFailed(above, BlobFailure.TOO_LARGE)),
            link.pulled<SessionEffect.BlobFailed>(),
        )
        assertContentEquals(atLimitBytes, link.pulled<SessionEffect.PublishBlob>().single().bytes)
    }

    @Test
    fun `an encrypted attachment at the size limit fits in a blob`() {
        val attachmentLimit = 100L * 1024L * 1024L
        assertEquals(attachmentLimit, BlobExchangeConfig.MAX_ATTACHMENT_SIZE)
        // An encrypted file adds a type byte, a 16-byte IV, a 32-byte MAC and up to 16 bytes of padding.
        assertTrue(BlobExchangeConfig().maxBlobSize >= attachmentLimit + 1 + 16 + 32 + 16)
        assertEquals(attachmentLimit + 1024L * 1024L, BlobExchangeConfig.MAX_BLOB_SIZE)
    }

    @Test
    fun `a missing blob is reported, and the next one can start`() {
        val (missing, _) = blob(10)
        val (present, bytes) = blob(10)
        val link = Link(mapOf(present to bytes))
        link.fromPuller(link.puller.want(missing))
        link.pump()
        assertEquals(listOf(SessionEffect.BlobUnavailable(missing)), link.pulled<SessionEffect.BlobUnavailable>())
        link.fromPuller(link.puller.want(present))
        link.pump()
        assertContentEquals(bytes, link.pulled<SessionEffect.PublishBlob>().single().bytes)
    }

    @Test
    fun `blobs are pulled one at a time`() {
        val (a, aBytes) = blob(2_500)
        val (b, bBytes) = blob(1_500)
        val link = Link(mapOf(a to aBytes, b to bBytes))
        val first = link.puller.want(a)
        assertEquals(1, first.size)
        // A want while a transfer runs starts nothing.
        assertEquals(emptyList(), link.puller.want(b))
        link.fromPuller(first)
        link.pump()
        assertEquals(listOf(a), link.pulled<SessionEffect.PublishBlob>().map { it.hash })
        link.fromPuller(link.puller.want(b))
        link.pump()
        assertEquals(listOf(a, b), link.pulled<SessionEffect.PublishBlob>().map { it.hash })
        assertEquals(listOf(0L, 1_000L, 2_000L, 0L, 1_000L), link.wants)
    }

    @Test
    fun `a stalled transfer fails after the timeout`() {
        val (hash, _) = blob(10)
        val puller = BlobExchange(crypto, OutgoingMessages(), config)
        puller.onTick(1_000L)
        puller.want(hash)
        assertEquals(emptyList(), puller.onTick(1_000L + BlobExchangeConfig.TIMEOUT_MS - 1))
        assertEquals(
            listOf(SessionEffect.BlobFailed(hash, BlobFailure.TIMEOUT)),
            puller.onTick(1_000L + BlobExchangeConfig.TIMEOUT_MS),
        )
    }

    @Test
    fun `a holder that keeps a window coming without finishing it loses the blob`() {
        val (hash, _) = blob(10)
        val puller = BlobExchange(crypto, OutgoingMessages(), config)
        val start = 1_000L
        puller.onTick(start)
        puller.want(hash)
        // A frame arrives every 10 seconds, so the transfer never stalls, but the window
        // never completes.
        var now = start
        while (now + 10_000L < start + BlobExchangeConfig.WINDOW_TIMEOUT_MS) {
            now += 10_000L
            puller.onDataFrame()
            assertEquals(emptyList(), puller.onTick(now))
        }
        puller.onDataFrame()
        assertEquals(
            listOf(SessionEffect.BlobFailed(hash, BlobFailure.TIMEOUT)),
            puller.onTick(start + BlobExchangeConfig.WINDOW_TIMEOUT_MS),
        )
    }

    @Test
    fun `a transfer started before the first tick gets the whole timeout`() {
        val (hash, _) = blob(10)
        val puller = BlobExchange(crypto, OutgoingMessages(), config)
        puller.want(hash)
        // The session clock runs since long before the transfer.
        val start = 1_800_000_000_000L
        assertEquals(emptyList(), puller.onTick(start))
        assertEquals(emptyList(), puller.onTick(start + BlobExchangeConfig.TIMEOUT_MS - 1))
        assertEquals(
            listOf(SessionEffect.BlobFailed(hash, BlobFailure.TIMEOUT)),
            puller.onTick(start + BlobExchangeConfig.TIMEOUT_MS),
        )
    }

    @Test
    fun `holders advertise their blobs`() {
        val holder = BlobExchange(crypto, OutgoingMessages(), config)
        val puller = BlobExchange(crypto, OutgoingMessages(), config)
        val frame = (holder.advertise(listOf("b", "a")).single() as SessionEffect.SendFrame).frame
        val message = (Reassembler().accept(frame) as ReassemblyResult.Complete).message
        assertEquals(
            listOf(SessionEffect.PeerBlobs(listOf("a", "b"))),
            puller.onMessage(FrameType.BLOB_HAVE, message.payload),
        )
    }

    @Test
    fun `the blob id is the base32 SHA-256`() {
        val hash = BlobExchange.hashOf(crypto, "abc".encodeToByteArray())
        assertEquals("xj4bnp4pahh6uqkbidpf3lrceoyagyndsylxvhfucd7wd4qacwwq", hash)
        assertTrue(hash.all { it.isLowerCase() || it.isDigit() })
    }

    @Test
    fun `an advertisement names no more blobs than its message takes`() {
        val hashes = List(BlobExchange.MAX_ADVERTISED + 100) { blob(8).first }
        val frames = BlobExchange(crypto).advertise(hashes).filterIsInstance<SessionEffect.SendFrame>()
        val reassembler = Reassembler()
        val message = assertIs<ReassemblyResult.Complete>(frames.map { reassembler.accept(it.frame) }.last()).message
        assertEquals(
            listOf(SessionEffect.PeerBlobs(hashes.sorted().take(BlobExchange.MAX_ADVERTISED))),
            BlobExchange(crypto).onMessage(FrameType.BLOB_HAVE, message.payload),
        )
    }
}
