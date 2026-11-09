package com.artemchep.keyguard.util.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Two peers in one process on 127.0.0.1, signaled directly. Runs on every
 * target that links the native library: desktop, Android host, iOS and macOS.
 */
class WebRtcLoopbackTest {
    private val loopback = WebRtcConfig(bindAddresses = listOf("127.0.0.1:0"))

    @Test
    fun twoPeersExchangeOrderedMessages() = realTime {
        val runtime = WebRtcRuntime.create()
        try {
            val offerer = runtime.createPeer(loopback)
            val answerer = runtime.createPeer(loopback)
            coroutineScope {
                val link = link(offerer, answerer)
                withTimeout(TIMEOUT) {
                    link.offererOpen.await()
                    link.answererOpen.await()
                }

                // 100 ordered messages, one of them the largest allowed, then
                // enough 64 KiB ones to fill the 1 MiB send buffer.
                val messages = List(100) { i -> message(i, if (i == 50) MAX_MESSAGE else 16 + i) } +
                    List(40) { i -> message(100 + i, MAX_MESSAGE) }
                messages.forEach { offerer.send(it) }
                val received = withTimeout(TIMEOUT) { List(messages.size) { link.received.receive() } }
                messages.zip(received).forEachIndexed { index, (expected, actual) ->
                    assertContentEquals(expected, actual, "message $index")
                }
                assertEquals(
                    WebRtcSelectedPair("host", "host", "udp"),
                    withTimeout(TIMEOUT) { link.offererPair.await() },
                )
                assertTrue(offerer.bufferedAmount() in 0L..SEND_BUFFER_LIMIT)
                withTimeout(TIMEOUT) { offerer.awaitBufferedAtMost(0L) }
                assertEquals(0L, offerer.bufferedAmount())

                // Cancelling a collector closes its peer.
                link.offererEvents.cancel()
                link.offererEvents.join()
                val closed = assertFailsWith<WebRtcException> { offerer.send(byteArrayOf(1)) }
                assertEquals(WebRtcFailure.CLOSED, closed.failure)
                offerer.close()
                answerer.close()
                link.answererEvents.join()
            }
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun failuresAreReportedWithStableCategories() = realTime {
        WebRtcRuntime.ensureAvailable()
        val runtime = WebRtcRuntime.create()
        try {
            val invalid = assertFailsWith<WebRtcException> {
                runtime.createPeer(WebRtcConfig(bindAddresses = listOf("localhost:0")))
            }
            assertEquals(WebRtcFailure.INVALID_ARGUMENT, invalid.failure)

            val peer = runtime.createPeer(loopback)
            val noChannel = assertFailsWith<WebRtcException> { peer.send(byteArrayOf(1)) }
            assertEquals(WebRtcFailure.INVALID_STATE, noChannel.failure)
            val badSdp = assertFailsWith<WebRtcException> {
                peer.setRemoteDescription(WebRtcSdpType.OFFER, "not an sdp")
            }
            assertEquals(WebRtcFailure.INVALID_ARGUMENT, badSdp.failure)
            val noChannelAmount = assertFailsWith<WebRtcException> { peer.bufferedAmount() }
            assertEquals(WebRtcFailure.INVALID_STATE, noChannelAmount.failure)
            peer.createChannel("keyguard")
            val tooLong = assertFailsWith<WebRtcException> { peer.send(ByteArray(MAX_MESSAGE + 1)) }
            assertEquals(WebRtcFailure.INPUT_TOO_LONG, tooLong.failure)
            // The core parses candidates; the bridges pass them through.
            val notCandidate = assertFailsWith<WebRtcException> { peer.addIceCandidate("not json") }
            assertEquals(WebRtcFailure.INVALID_ARGUMENT, notCandidate.failure)
            val longCandidate = assertFailsWith<WebRtcException> {
                peer.addIceCandidate("""{"candidate":"${"x".repeat(MAX_CANDIDATE)}"}""")
            }
            assertEquals(WebRtcFailure.INPUT_TOO_LONG, longCandidate.failure)
            assertEquals(0L, peer.bufferedAmount())
        } finally {
            runtime.shutdown()
        }
        runtime.shutdown()
        val closed = assertFailsWith<WebRtcException> { runtime.createPeer(loopback) }
        assertEquals(WebRtcFailure.CLOSED, closed.failure)
    }

    private class Link(
        val offererEvents: Job,
        val answererEvents: Job,
        val offererOpen: CompletableDeferred<Unit>,
        val answererOpen: CompletableDeferred<Unit>,
        val offererPair: CompletableDeferred<WebRtcSelectedPair>,
        val received: Channel<ByteArray>,
    )

    /** Starts both event loops, forwarding candidates, and negotiates. */
    private suspend fun CoroutineScope.link(offerer: WebRtcPeer, answerer: WebRtcPeer): Link {
        val offererOpen = CompletableDeferred<Unit>()
        val answererOpen = CompletableDeferred<Unit>()
        val offererPair = CompletableDeferred<WebRtcSelectedPair>()
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val offererEvents = launch {
            offerer.events().collect { event ->
                when (event) {
                    is WebRtcEvent.IceCandidate -> answerer.addIceCandidate(event.json)
                    WebRtcEvent.ChannelOpen -> offererOpen.complete(Unit)
                    is WebRtcEvent.SelectedPair -> offererPair.complete(event.pair)
                    else -> Unit
                }
            }
        }
        val answererEvents = launch {
            answerer.events().collect { event ->
                when (event) {
                    is WebRtcEvent.IceCandidate -> offerer.addIceCandidate(event.json)
                    WebRtcEvent.ChannelOpen -> answererOpen.complete(Unit)
                    is WebRtcEvent.ChannelMessage -> received.send(event.data)
                    else -> Unit
                }
            }
        }
        offerer.createChannel("keyguard")
        answerer.setRemoteDescription(WebRtcSdpType.OFFER, offerer.createOffer())
        offerer.setRemoteDescription(WebRtcSdpType.ANSWER, answerer.createAnswer())
        return Link(offererEvents, answererEvents, offererOpen, answererOpen, offererPair, received)
    }

    private fun message(sequence: Int, size: Int): ByteArray =
        ByteArray(size) { index -> (sequence + index).toByte() }

    /** `runTest` skips delays; native calls need the real clock. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 60.seconds) { withContext(Dispatchers.Default) { block() } }

    private companion object {
        const val MAX_MESSAGE = 64 * 1024
        const val MAX_CANDIDATE = 4 * 1024
        const val SEND_BUFFER_LIMIT = 1024L * 1024L
        val TIMEOUT = 20.seconds
    }
}
