package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs both patterns through the public API against the real native library on
 * every platform whose tests run.
 */
class NativeCryptoNoiseTest {
    private val noise = NativeCrypto.noise

    @Test
    fun xxPsk3HandshakeAndTransportRoundTrip() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        noise.xxPsk3(true, alice.privateKey, PSK, PROLOGUE).use { initiator ->
            noise.xxPsk3(false, bob.privateKey, PSK, PROLOGUE).use { responder ->
                assertEquals(NativeNoisePattern.XX_PSK3, initiator.pattern)
                assertNull(initiator.remoteStaticKey())

                assertContentEquals(EMPTY, responder.readMessage(initiator.writeMessage()))
                assertContentEquals(EMPTY, initiator.readMessage(responder.writeMessage()))
                assertContentEquals(bob.publicKey, initiator.remoteStaticKey())
                assertFalse(responder.isFinished())
                assertContentEquals(HELLO, responder.readMessage(initiator.writeMessage(HELLO)))
                assertContentEquals(alice.publicKey, responder.remoteStaticKey())

                assertTransportsTalk(initiator, responder)
            }
        }
    }

    @Test
    fun kkHandshakeAndTransportRoundTrip() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        noise.kk(true, alice.privateKey, bob.publicKey, PROLOGUE).use { initiator ->
            noise.kk(false, bob.privateKey, alice.publicKey, PROLOGUE).use { responder ->
                assertEquals(NativeNoisePattern.KK, initiator.pattern)
                assertContentEquals(bob.publicKey, initiator.remoteStaticKey())

                assertContentEquals(HELLO, responder.readMessage(initiator.writeMessage(HELLO)))
                assertContentEquals(EMPTY, initiator.readMessage(responder.writeMessage()))

                assertTransportsTalk(initiator, responder)
            }
        }
    }

    @Test
    fun aKeyPairIsDerivedFromItsPrivateKey() {
        val generated = noise.generateKeyPair()
        val derived = noise.keyPairOf(generated.privateKey)
        assertContentEquals(generated.privateKey, derived.privateKey)
        assertContentEquals(generated.publicKey, derived.publicKey)

        val failure = assertFailsWith<NativeCryptoException> {
            noise.keyPairOf(ByteArray(NativeNoiseKeyPair.KEY_SIZE - 1))
        }
        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun anEmptyPrivateKeyIsRejectedInsteadOfGeneratingOne() {
        val failure = assertFailsWith<NativeCryptoException> {
            noise.keyPairOf(ByteArray(0))
        }
        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun aWrongPskFailsBeforeAnyPayloadIsAccepted() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        val wrongPsk = ByteArray(NativeCryptoNoise.PSK_SIZE) { 0x24 }
        noise.xxPsk3(true, alice.privateKey, PSK, PROLOGUE).use { initiator ->
            noise.xxPsk3(false, bob.privateKey, wrongPsk, PROLOGUE).use { responder ->
                responder.readMessage(initiator.writeMessage())
                initiator.readMessage(responder.writeMessage())

                val message = initiator.writeMessage(HELLO)
                val failure = assertFailsWith<NativeCryptoException> {
                    responder.readMessage(message)
                }
                assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
                // The failure ended the responder's session.
                val ended = assertFailsWith<NativeCryptoException> {
                    responder.isFinished()
                }
                assertEquals(NativeCryptoErrorCode.INVALID_SESSION, ended.code)
            }
        }
    }

    @Test
    fun aWrongPinnedKeyFailsTheKkHandshake() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        val mallory = noise.generateKeyPair()
        noise.kk(true, alice.privateKey, mallory.publicKey, PROLOGUE).use { initiator ->
            noise.kk(false, bob.privateKey, alice.publicKey, PROLOGUE).use { responder ->
                val failure = assertFailsWith<NativeCryptoException> {
                    responder.readMessage(initiator.writeMessage(HELLO))
                }
                assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
            }
        }
    }

    @Test
    fun aDifferentPrologueFailsTheHandshake() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        noise.kk(true, alice.privateKey, bob.publicKey, PROLOGUE).use { initiator ->
            noise.kk(false, bob.privateKey, alice.publicKey).use { responder ->
                val failure = assertFailsWith<NativeCryptoException> {
                    responder.readMessage(initiator.writeMessage())
                }
                assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
            }
        }
    }

    @Test
    fun argumentsOfTheWrongSizeAreRejected() {
        val alice = noise.generateKeyPair()
        val failure = assertFailsWith<NativeCryptoException> {
            noise.xxPsk3(true, alice.privateKey, ByteArray(NativeCryptoNoise.PSK_SIZE - 1))
        }
        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, failure.code)
        // An empty pinned key is absent, which KK does not allow.
        val absent = assertFailsWith<NativeCryptoException> {
            noise.kk(true, alice.privateKey, ByteArray(0))
        }
        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, absent.code)
    }

    @Test
    fun theHashOfAnUnfinishedHandshakeIsRefusedWithoutEndingIt() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        noise.kk(true, alice.privateKey, bob.publicKey).use { initiator ->
            val failure = assertFailsWith<NativeCryptoException> {
                initiator.handshakeHash()
            }
            assertEquals(NativeCryptoErrorCode.CRYPTO_FAILURE, failure.code)
            assertFalse(initiator.isFinished())
        }
    }

    @Test
    fun closedHandshakesAndTransportsAreGone() {
        val alice = noise.generateKeyPair()
        val bob = noise.generateKeyPair()
        val initiator = noise.kk(true, alice.privateKey, bob.publicKey)
        val responder = noise.kk(false, bob.privateKey, alice.publicKey)
        responder.readMessage(initiator.writeMessage())
        initiator.readMessage(responder.writeMessage())
        val transport = initiator.intoTransport()
        // The spent handshake is gone; closing it is a no-op.
        assertEquals(
            NativeCryptoErrorCode.INVALID_SESSION,
            assertFailsWith<NativeCryptoException> { initiator.writeMessage() }.code,
        )
        initiator.close()

        transport.close()
        transport.close()
        assertEquals(
            NativeCryptoErrorCode.INVALID_SESSION,
            assertFailsWith<NativeCryptoException> { transport.writeMessage(HELLO) }.code,
        )
        responder.close()
    }

    private fun assertTransportsTalk(
        initiator: NativeNoiseHandshake,
        responder: NativeNoiseHandshake,
    ) {
        assertTrue(initiator.isFinished())
        assertTrue(responder.isFinished())
        assertContentEquals(initiator.handshakeHash(), responder.handshakeHash())
        initiator.intoTransport().use { left ->
            responder.intoTransport().use { right ->
                assertEquals(0L, left.sendNonce())
                val first = left.writeMessage(HELLO)
                val second = left.writeMessage(EMPTY)
                assertEquals(HELLO.size + TAG_SIZE, first.size)
                assertEquals(2L, left.sendNonce())
                assertContentEquals(HELLO, right.readMessage(first))
                assertContentEquals(EMPTY, right.readMessage(second))

                val reply = right.writeMessage(HELLO)
                assertContentEquals(HELLO, left.readMessage(reply))
                assertEquals(1L, right.sendNonce())

                // A replayed message fails and ends the transport.
                val replay = assertFailsWith<NativeCryptoException> { left.readMessage(reply) }
                assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, replay.code)
            }
        }
    }
}

private val PSK = ByteArray(NativeCryptoNoise.PSK_SIZE) { 0x42 }

private val PROLOGUE = "keyguard-noise-test".encodeToByteArray()

private val HELLO = "hello".encodeToByteArray()

private val EMPTY = ByteArray(0)

private const val TAG_SIZE = 16
