package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.session.BlobExchangeConfig
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import com.artemchep.keyguard.feature.p2psync.session.SessionEffect
import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyguardPeerBlobsTest {
    /** Blobs whose reads wait for [gate], recording what was read and what the peer said it holds. */
    private class GatedBlobs : P2pGroupBlobs {
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val peerBlobs = mutableListOf<Collection<String>>()

        override val config = BlobExchangeConfig()
        override val version: StateFlow<Long> = MutableStateFlow(0L)

        override suspend fun held(): Set<String> = emptySet()

        override suspend fun read(hash: String): ByteArray {
            reads += hash
            gate.await()
            return ByteArray(1)
        }

        override fun onPeerBlobs(peerId: String, hashes: Collection<String>) {
            peerBlobs += hashes
        }

        override fun claimNext(peerId: String, resume: String?): String? = null

        override fun wanted(): Set<String> = emptySet()

        override suspend fun onReceived(peerId: String, hash: String, bytes: ByteArray) = Unit

        override fun onFailed(peerId: String, hash: String) = Unit

        override fun onSessionEnded(peerId: String) = Unit
    }

    /** One blob, handed over while [shared]. */
    private class SwitchedBlobs(val hash: String, val bytes: ByteArray) : P2pGroupBlobs {
        var shared = true
        var reads = 0

        override val config = BlobExchangeConfig()
        override val version: StateFlow<Long> = MutableStateFlow(0L)

        override suspend fun held(): Set<String> = if (shared) setOf(hash) else emptySet()

        override suspend fun read(hash: String): ByteArray? {
            reads++
            return bytes.takeIf { shared && hash == this.hash }
        }

        override fun onPeerBlobs(peerId: String, hashes: Collection<String>) = Unit

        override fun claimNext(peerId: String, resume: String?): String? = null

        override fun wanted(): Set<String> = emptySet()

        override suspend fun onReceived(peerId: String, hash: String, bytes: ByteArray) = Unit

        override fun onFailed(peerId: String, hash: String) = Unit

        override fun onSessionEnded(peerId: String) = Unit
    }

    private val exchange = BlobExchange(NativeP2pCrypto(NativeCryptoGenerator()))

    @Test
    fun `a blob is served only while its category is shared`() = runTest {
        val blobs = SwitchedBlobs(hash = "words", bytes = ByteArray(4) { it.toByte() })
        val sent = mutableListOf<FrameType>()
        val peer = KeyguardPeerBlobs(peerId = "peer", blobs = blobs, changes = emptyFlow())
        val job = launch {
            peer.run(flowOf(true)) { block ->
                exchange.block().filterIsInstance<SessionEffect.SendFrame>().mapNotNullTo(sent) { it.frame.type }
            }
        }
        runCurrent()
        sent.clear()
        // A window past the end keeps nothing here.
        peer.post(SessionEffect.ReadBlob("words", offset = 5, maxLength = 1))
        runCurrent()
        peer.post(SessionEffect.ReadBlob("words", offset = 0, maxLength = 2))
        runCurrent()
        // The next window comes from the blob kept here.
        peer.post(SessionEffect.ReadBlob("words", offset = 2, maxLength = 2))
        runCurrent()
        assertEquals(listOf(FrameType.BLOB_NOPE, FrameType.BLOB_DATA, FrameType.BLOB_DATA), sent)
        assertEquals(2, blobs.reads)

        // The last window lets the blob go: asking for it again reads it again.
        peer.post(SessionEffect.ReadBlob("words", offset = 2, maxLength = 2))
        runCurrent()
        assertEquals(FrameType.BLOB_DATA, sent.last())
        assertEquals(3, blobs.reads)

        // The user stops syncing the category: the kept blob is not served either.
        blobs.shared = false
        peer.post(SessionEffect.ReadBlob("words", offset = 0, maxLength = 2))
        runCurrent()
        assertEquals(FrameType.BLOB_NOPE, sent.last())
        job.cancel()
    }

    @Test
    fun `a peer that asks faster than the blobs are read gets its newest window only`() = runTest {
        val blobs = GatedBlobs()
        val peer = KeyguardPeerBlobs(peerId = "peer", blobs = blobs, changes = emptyFlow())
        val job = launch { peer.run(flowOf(true)) { block -> exchange.block() } }
        runCurrent()
        // The first window holds the reader while the peer keeps asking.
        peer.post(SessionEffect.ReadBlob("first", offset = 0, maxLength = 1))
        runCurrent()
        repeat(10_000) { index ->
            peer.post(SessionEffect.ReadBlob("blob $index", offset = 0, maxLength = 1))
            peer.post(SessionEffect.PeerBlobs(listOf("held $index")))
        }
        blobs.gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "blob 9999"), blobs.reads)
        assertEquals(listOf<Collection<String>>(listOf("held 9999")), blobs.peerBlobs)
        job.cancel()
    }
}
