package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import com.artemchep.keyguard.provider.keyguard.session.realTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseByteStreamTest {
    private val alice = NativeCryptoNoise.generateKeyPair()
    private val bob = NativeCryptoNoise.generateKeyPair()
    private val psk = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `xxpsk3 learns both statics and carries messages both ways`() = realTime {
        val (pa, pb) = TestPipe.pair()
        val (a, b) = handshakes(
            { stream -> xx(stream, initiator = true, keyPair = alice, psk = psk) },
            { stream -> xx(stream, initiator = false, keyPair = bob, psk = psk) },
            pa,
            pb,
        )
        assertContentEquals(bob.publicKey, a.remoteStatic)
        assertContentEquals(alice.publicKey, b.remoteStatic)
        assertContentEquals(a.handshakeHash, b.handshakeHash)
        // The id of the suite, then three handshake messages; the first two carry no
        // payload, only keys.
        assertEquals(3, pa.written.size)
        assertContentEquals(byteArrayOf(NoiseSuite.XX_PSK3.id), pa.written.first())
        assertEquals(1, pb.written.size)

        exchange(a, b)
    }

    @Test
    fun `kk carries messages both ways`() = realTime {
        val (pa, pb) = TestPipe.pair()
        val (a, b) = handshakes(
            { stream -> kk(stream, initiator = true, keyPair = alice, remote = bob.publicKey) },
            { stream -> kk(stream, initiator = false, keyPair = bob, remote = alice.publicKey) },
            pa,
            pb,
        )
        assertContentEquals(bob.publicKey, a.remoteStatic)
        assertContentEquals(a.handshakeHash, b.handshakeHash)
        // The initiator names the suite before the two handshake messages.
        assertContentEquals(byteArrayOf(NoiseSuite.KK_HFS.id), pa.written.first())
        assertEquals(2, pa.written.size)
        exchange(a, b)
    }

    @Test
    fun `a wrong psk or an unexpected static fails the handshake`() = realTime {
        val (pa, pb) = TestPipe.pair()
        assertFails {
            handshakes(
                { stream -> xx(stream, initiator = true, keyPair = alice, psk = psk) },
                { stream -> xx(stream, initiator = false, keyPair = bob, psk = ByteArray(32)) },
                pa,
                pb,
            )
        }
        val (qa, qb) = TestPipe.pair()
        val mallory = NativeCryptoNoise.generateKeyPair()
        assertFails {
            handshakes(
                { stream -> kk(stream, initiator = true, keyPair = alice, remote = bob.publicKey) },
                { stream -> kk(stream, initiator = false, keyPair = mallory, remote = alice.publicKey) },
                qa,
                qb,
            )
        }
    }

    @Test
    fun `a tampered message ends the stream`() = realTime {
        var count = 0
        // The initiator writes the id of the suite, then the handshake; flip a bit of the
        // message after them.
        val (pa, pb) = TestPipe.pair { bytes ->
            count += 1
            if (count == 3) bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else bytes
        }
        val (a, b) = handshakes(
            { stream -> kk(stream, initiator = true, keyPair = alice, remote = bob.publicKey) },
            { stream -> kk(stream, initiator = false, keyPair = bob, remote = alice.publicKey) },
            pa,
            pb,
        )
        a.write(byteArrayOf(1, 2, 3))
        assertNull(withTimeout(TIMEOUT) { b.read() })
    }

    @Test
    fun `a responder takes no other suite than its own`() = realTime {
        val (pa, pb) = TestPipe.pair()
        coroutineScope {
            launch { pa.write(byteArrayOf(NoiseSuite.XX_PSK3.id)) }
            assertFailsWith<NoiseSuiteException> {
                kk(pb, initiator = false, keyPair = bob, remote = alice.publicKey)
            }
        }
        // The responder closed the stream.
        assertNull(withTimeout(TIMEOUT) { pb.read() })
    }

    @Test
    fun `the session ends before its nonce runs out`() = realTime {
        val (pa, pb) = TestPipe.pair()
        val (a, _) = handshakes(
            { stream ->
                NoiseByteStream.handshake(
                    stream = stream,
                    suite = NoiseSuite.KK_HFS,
                    initiator = true,
                    localPrivateKey = alice.privateKey,
                    remotePublicKey = bob.publicKey,
                    maxSendNonce = 3,
                )
            },
            { stream -> kk(stream, initiator = false, keyPair = bob, remote = alice.publicKey) },
            pa,
            pb,
        )
        repeat(3) { a.write(byteArrayOf(it.toByte())) }
        assertFailsWith<NativeCryptoException> { a.write(byteArrayOf(9)) }
    }

    @Test
    fun `a silent peer times the handshake out and closes the stream`() = realTime {
        val (pa, _) = TestPipe.pair()
        assertFailsWith<TransportException> {
            NoiseByteStream.handshake(
                stream = pa,
                suite = NoiseSuite.KK_HFS,
                initiator = true,
                localPrivateKey = alice.privateKey,
                remotePublicKey = bob.publicKey,
                timeoutMs = 300L,
            )
        }
        assertNull(pa.read())
    }

    private suspend fun exchange(a: NoiseByteStream, b: NoiseByteStream) = coroutineScope {
        val forward = List(MESSAGES) { i -> ByteArray(1 + i * 650) { (i + it).toByte() } }
        val backward = List(MESSAGES) { i -> ByteArray(32 * 1024 - i) { (i - it).toByte() } }
        launch { forward.forEach { a.write(it) } }
        launch { backward.forEach { b.write(it) } }
        forward.forEach { assertContentEquals(it, withTimeout(TIMEOUT) { b.read() }) }
        backward.forEach { assertContentEquals(it, withTimeout(TIMEOUT) { a.read() }) }
        a.close()
        assertTrue(b.read() == null)
    }

    private suspend fun handshakes(
        initiator: suspend (ByteStream) -> NoiseByteStream,
        responder: suspend (ByteStream) -> NoiseByteStream,
        a: ByteStream,
        b: ByteStream,
    ): Pair<NoiseByteStream, NoiseByteStream> = coroutineScope {
        val sb = async { responder(b) }
        val sa = initiator(a)
        sa to withTimeout(TIMEOUT) { sb.await() }
    }

    private suspend fun xx(stream: ByteStream, initiator: Boolean, keyPair: NativeNoiseKeyPair, psk: ByteArray) =
        NoiseByteStream.handshake(
            stream = stream,
            suite = NoiseSuite.XX_PSK3,
            initiator = initiator,
            localPrivateKey = keyPair.privateKey,
            psk = psk,
            timeoutMs = TIMEOUT,
        )

    private suspend fun kk(stream: ByteStream, initiator: Boolean, keyPair: NativeNoiseKeyPair, remote: ByteArray) =
        NoiseByteStream.handshake(
            stream = stream,
            suite = NoiseSuite.KK_HFS,
            initiator = initiator,
            localPrivateKey = keyPair.privateKey,
            remotePublicKey = remote,
            timeoutMs = TIMEOUT,
        )

    private companion object {
        const val MESSAGES = 50
        const val TIMEOUT = 10_000L
    }
}
