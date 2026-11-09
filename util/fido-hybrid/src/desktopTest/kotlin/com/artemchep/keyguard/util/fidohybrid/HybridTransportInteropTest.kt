package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent JCA browser talks to the real Kotlin/Rust phone transport, without BLE or a relay. */
class HybridTransportInteropTest {
    @Test
    fun cancellationStopsAdvertisingAndClearsQrSecret() =
        runTest {
            val advertised = CompletableDeferred<Unit>()
            val qr = HybridQr.parse(QR)
            var stopped = false
            val job =
                launch {
                    HybridTransport(aaguid = ByteArray(16)).run(
                        qr = qr,
                        connector = { _, _, block -> block(TestTunnel()) },
                        advertiser =
                            object : HybridAdvertiser {
                                override suspend fun start(payload: ByteArray) {
                                    advertised.complete(Unit)
                                }

                                override fun stop() {
                                    stopped = true
                                }
                            },
                        onWaiting = {},
                        authenticator = TestAuthenticator { error("No request was received") },
                    )
                }
            advertised.await()
            job.cancelAndJoin()
            assertTrue(stopped)
            assertTrue(qr.secret.all { it == 0.toByte() })
        }

    @Test
    fun browserCompletesQrHandshakeProbesAndAssertion() =
        runTest {
            val advertisement = CompletableDeferred<ByteArray>()
            var stopped = false
            var assertions = 0
            var guards = 0
            val advertiser =
                object : HybridAdvertiser {
                    override suspend fun start(payload: ByteArray) {
                        advertisement.complete(payload)
                    }

                    override fun stop() {
                        stopped = true
                    }
                }
            val qr = HybridQr.parse(QR)
            val tunnel = TestTunnel()
            val updateSent = CompletableDeferred<Unit>()
            val success =
                HybridTransport(aaguid = ByteArray(16)).run(
                    qr = qr,
                    connector = { relay, id, block ->
                        assertEquals(HybridRelay.GOOGLE, relay)
                        coroutineScope {
                            val browser = async { browser(id, tunnel, advertisement.await(), updateSent) }
                            block(tunnel)
                            browser.await()
                        }
                    },
                    advertiser = advertiser,
                    onWaiting = {},
                    authenticator =
                        TestAuthenticator { request ->
                            // An update while the user is choosing must not cancel the request.
                            updateSent.await()
                            assertions++
                            assertEquals("example.com", request.options.rpId)
                            assertContentEquals(ByteArray(32) { 7 }, request.options.clientDataHash)
                            HybridResult.Success(
                                WebAuthnAssertionResult(
                                byteArrayOf(1), byteArrayOf(2), byteArrayOf(0x30, 0), byteArrayOf(3),
                            ),
                            )
                        },
                    guard = { guards++ },
                )
            assertTrue(success)
            assertTrue(stopped)
            assertEquals(1, assertions)
            assertEquals(4, guards)
            assertTrue(qr.secret.all { it == 0.toByte() })
        }

    private suspend fun browser(
        id: String,
        tunnel: TestTunnel,
        advert: ByteArray,
        updateSent: CompletableDeferred<Unit>,
    ) {
        val secret = ByteArray(16) { it.toByte() }
        val expectedId = hkdf(secret, byteArrayOf(), byteArrayOf(2, 0, 0, 0), 16)
        assertEquals(expectedId.toHexString(), id)
        val eidKeys = hkdf(secret, byteArrayOf(), byteArrayOf(1, 0, 0, 0), 64)
        assertContentEquals(hmac(eidKeys.copyOfRange(32, 64), advert.copyOf(16)).copyOf(4), advert.copyOfRange(16, 20))
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(eidKeys.copyOf(32), "AES"))
        val eid = cipher.doFinal(advert.copyOf(16))
        assertContentEquals(tunnel.routingId, eid.copyOfRange(11, 14))
        assertContentEquals(byteArrayOf(0, 0), eid.copyOfRange(14, 16))
        val psk = hkdf(secret, eid, byteArrayOf(3, 0, 0, 0), 32)
        val noise = BrowserNoise(psk)
        tunnel.toPhone.send(noise.hello())
        val keys = noise.finish(tunnel.fromPhone.receive())
        val post = traffic(false, keys.copyOfRange(32, 64), 0, tunnel.fromPhone.receive())
        val info = HybridCbor.decode(post) as Map<*, *>
        assertTrue(info[1L] is ByteArray)
        // Browser preflight is answered before the one interactive assertion.
        tunnel.toPhone.send(traffic(true, keys.copyOf(32), 0, byteArrayOf(1, 4)))
        val probe = traffic(false, keys.copyOfRange(32, 64), 1, tunnel.fromPhone.receive())
        assertContentEquals(byteArrayOf(1, 0), probe.copyOf(2))
        val request = byteArrayOf(1, 2) + HybridCbor.encode(mapOf(1 to "example.com", 2 to ByteArray(32) { 7 }))
        tunnel.toPhone.send(traffic(true, keys.copyOf(32), 1, request))
        tunnel.toPhone.send(traffic(true, keys.copyOf(32), 2, byteArrayOf(2) + HybridCbor.encode(emptyMap<Int, Any>())))
        updateSent.complete(Unit)
        val response = traffic(false, keys.copyOfRange(32, 64), 2, tunnel.fromPhone.receive())
        assertContentEquals(byteArrayOf(1, 0), response.copyOf(2))
        val assertion = HybridCbor.decode(response.copyOfRange(2, response.size)) as Map<*, *>
        assertContentEquals(byteArrayOf(0x30, 0), assertion[3L] as ByteArray)
    }

    private class TestAuthenticator(
        private val assert: suspend (HybridAssertionRequest) -> HybridResult<WebAuthnAssertionResult>,
    ) : HybridAuthenticator {
        override suspend fun getAssertion(request: HybridAssertionRequest) = assert(request)

        override suspend fun makeCredential(request: HybridRegistrationRequest):
            HybridResult<WebAuthnCredentialCreationResult> =
            HybridResult.Denied
    }

    private class TestTunnel : HybridTunnel {
        override val routingId = byteArrayOf(4, 5, 6)
        val toPhone = Channel<ByteArray>(2)
        val fromPhone = Channel<ByteArray>(2)

        override val incoming = toPhone

        override suspend fun send(bytes: ByteArray) {
            fromPhone.send(bytes)
        }
    }

    private class BrowserNoise(
        psk: ByteArray,
    ) {
        private var ck = "Noise_KNpsk0_P256_AESGCM_SHA256".encodeToByteArray().copyOf(32)
        private var hash = ck.copyOf()
        private var key = byteArrayOf()
        private val ephemeral =
            hex(
                "04550f471003f3df97c3df506ac797f6721fb1a1fb7b8f6f83d224498a65c88e241" +
                    "36093d7012e509a73715cbd0b00a3cc0ff4b5c01b3ffa196ab1fb327036b8e6",
            )

        init {
            mixHash(byteArrayOf(1))
            mixHash(
                hex(
                    "046ff03b949241ce1dadd43519e6960e0a85b41a69a05c328103aa2bce1594ca16" +
                        "3c4f753a55bf01dc53f6c0b0c7eee78b40c6ff7d25a96e2282b989cef71c144a",
                ),
            )
            val material = hkdf(psk, ck, byteArrayOf(), 96)
            ck = material.copyOf(32)
            mixHash(material.copyOfRange(32, 64))
            key = material.copyOfRange(64, 96)
        }

        fun hello(): ByteArray {
            mixHash(ephemeral)
            mixKey(ephemeral)
            val proof = gcm(true, key, ByteArray(12), hash, byteArrayOf())
            mixHash(proof)
            return ephemeral + proof
        }

        fun finish(reply: ByteArray): ByteArray {
            assertEquals(81, reply.size)
            val peer = reply.copyOf(65)
            mixHash(peer)
            mixKey(peer)
            val parameters =
                AlgorithmParameters
                    .getInstance("EC")
                    .apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, peer.copyOfRange(1, 33)), BigInteger(1, peer.copyOfRange(33, 65)))
            val factory = KeyFactory.getInstance("EC")
            val public = factory.generatePublic(ECPublicKeySpec(point, parameters))
            for (scalar in listOf(2, 1)) {
                val spec = ECPrivateKeySpec(BigInteger(1, ByteArray(32) { scalar.toByte() }), parameters)
                val privateKey = factory.generatePrivate(spec)
                val agreement = KeyAgreement.getInstance("ECDH")
                agreement.init(privateKey)
                agreement.doPhase(public, true)
                mixKey(agreement.generateSecret())
            }
            assertTrue(gcm(false, key, ByteArray(12), hash, reply.copyOfRange(65, 81)).isEmpty())
            return hkdf(byteArrayOf(), ck, byteArrayOf(), 64)
        }

        private fun mixHash(value: ByteArray) {
            hash = MessageDigest.getInstance("SHA-256").digest(hash + value)
        }

        private fun mixKey(value: ByteArray) {
            val material = hkdf(value, ck, byteArrayOf(), 64)
            ck = material.copyOf(32)
            key = material.copyOfRange(32, 64)
        }
    }

    private companion object {
        const val QR =
            "FIDO:/6767604935059062948727942671537211028307910354016841417559286341" +
                "33814168227179318566500564050807297738025421010491811870057950297627316200281471117231106"

        fun hex(value: String) = value.hexToByteArray()

        fun hmac(
            key: ByteArray,
            value: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
                doFinal(value)
            }

        fun hkdf(
            input: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            val extracted = hmac(salt, input)
            var previous = byteArrayOf()
            var result = byteArrayOf()
            for (i in 1..((length + 31) / 32)) {
                previous = hmac(extracted, previous + info + byteArrayOf(i.toByte()))
                result += previous
            }
            return result.copyOf(length)
        }

        fun gcm(
            encrypt: Boolean,
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            data: ByteArray,
        ): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce),
                )
                updateAAD(aad)
                doFinal(data)
            }

        fun traffic(
            encrypt: Boolean,
            key: ByteArray,
            counter: Int,
            data: ByteArray,
        ): ByteArray {
            val nonce = ByteArray(12)
            repeat(4) { nonce[11 - it] = (counter ushr (8 * it)).toByte() }
            return if (encrypt) {
                val padding = 32 - data.size % 32
                val padded = data.copyOf(data.size + padding)
                padded[padded.lastIndex] = (padding - 1).toByte()
                gcm(true, key, nonce, byteArrayOf(), padded)
            } else {
                val padded = gcm(false, key, nonce, byteArrayOf(), data)
                padded.copyOf(padded.size - 1 - (padded.last().toInt() and 255))
            }
        }
    }
}
