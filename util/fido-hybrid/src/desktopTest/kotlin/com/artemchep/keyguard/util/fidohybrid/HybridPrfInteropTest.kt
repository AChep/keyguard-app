package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticatorDataFactory
import com.artemchep.keyguard.util.webauthn.WebAuthnCredential
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnPrfValues
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** JCA platform exercises the real CTAP session, native crypto, and WebAuthn authenticator. */
class HybridPrfInteropTest {
    @Test
    fun registrationAndAssertionMatchProviderPrfForBothProtocols() =
        runTest {
            for (protocol in listOf(1, 2)) {
                val handler = Authenticator()
                val session = HybridCtapSession(ByteArray(16))
                try {
                    val browser = Browser(protocol)
                    browser.connect(session, handler)
                    val registered = session.handle(registration(browser.input()), handler)
                    assertTrue(registered.finished)
                    assertEquals(0, registered.bytes[0].toInt())
                    val attestation = body(registered.bytes)
                    assertEquals("none", attestation[1L])
                    val authData = attestation[2L] as ByteArray
                    val idLength = ((authData[53].toInt() and 255) shl 8) or (authData[54].toInt() and 255)
                    // ES256 COSE keys have two fixed-size coordinates and encode to 77 bytes.
                    val coseStart = 55 + idLength
                    val cose = HybridCbor.decode(authData.copyOfRange(coseStart, coseStart + 77)) as Map<*, *>
                    val extensions = HybridCbor.decode(authData.copyOfRange(coseStart + 77, authData.size)) as Map<*, *>
                    assertEquals(true, extensions["hmac-secret"])
                    assertEquals(0xc5, authData[32].toInt() and 0xc5)
                    val registeredPrf = browser.decrypt(extensions["hmac-secret-mc"] as ByteArray)
                    val credential = assertNotNull(handler.credential)
                    val secret = PasskeyBase64.decode(assertNotNull(credential.prfSecret))
                    val expected =
                        hmac(secret, browser.salts.copyOf(32)) +
                            hmac(secret, browser.salts.copyOfRange(32, 64))
                    assertContentEquals(expected, registeredPrf)

                    verifyProvider(handler.engine, credential, expected)
                    session.clear()

                    // A new session has a new ECDH agreement; the credential's PRF stays the same.
                    val next = Browser(protocol)
                    next.connect(session, handler)
                    val asserted = session.handle(assertion(next.input()), handler)
                    assertEquals(0, asserted.bytes[0].toInt())
                    val response = body(asserted.bytes)
                    val signedData = response[2L] as ByteArray
                    val signedExtensions = HybridCbor.decode(signedData.copyOfRange(37, signedData.size)) as Map<*, *>
                    assertContentEquals(expected, next.decrypt(signedExtensions["hmac-secret"] as ByteArray))
                    assertEquals(0x85, signedData[32].toInt() and 0xc5)
                    assertTrue(verify(cose, signedData + ByteArray(32) { 8 }, response[3L] as ByteArray))
                    assertTrue(requireNotNull(handler.lastSalts).first.all { it == 0.toByte() })

                    verifyLegacy(session, handler, next, credential)
                    secret.fill(0)
                } finally {
                    session.clear()
                }
            }
        }

    private fun verifyProvider(
        engine: WebAuthnAuthenticator,
        credential: WebAuthnCredential,
        expected: ByteArray,
    ) {
        val provider =
            engine.getAssertion(
                WebAuthnAssertionHashRequest(
                    "example.com",
                    ByteArray(32),
                    "required",
                    WebAuthnAllowedCredentialDescriptors.NONE,
                    prf = WebAuthnPrfValues(INPUT_ONE, INPUT_TWO),
                    prfSalted = false,
                ),
                credential,
                true,
            )
        assertContentEquals(
            expected,
            requireNotNull(provider.prfResults).first + requireNotNull(provider.prfResults?.second),
        )
        provider.signature.fill(0)
        provider.prfResults?.clear()
    }

    private suspend fun verifyLegacy(
        session: HybridCtapSession,
        handler: Authenticator,
        browser: Browser,
        credential: WebAuthnCredential,
    ) {
        // Imported credentials without a PRF secret must not silently acquire a new one.
        handler.credential = credential.copy(prfSecret = null)
        val legacy = session.handle(assertion(browser.input()), handler)
        assertEquals(37, (body(legacy.bytes)[2L] as ByteArray).size)
        assertEquals(0, (body(legacy.bytes)[2L] as ByteArray)[32].toInt() and 0x80)
    }

    @Test
    fun singleSaltAndDefaultProtocolReturnOneOutput() =
        runTest {
            for (protocol in listOf(1, 2)) {
                val handler = Authenticator()
                val session = HybridCtapSession(ByteArray(16))
                try {
                    assertEquals(0, session.handle(registration(null), handler).bytes[0].toInt())
                    val browser = Browser(protocol, twoInputs = false)
                    browser.connect(session, handler)
                    val input = browser.input().let { if (protocol == 1) it - 4 else it }
                    val asserted = session.handle(assertion(input), handler)
                    assertEquals(0, asserted.bytes[0].toInt())
                    val data = body(asserted.bytes)[2L] as ByteArray
                    val extensions = HybridCbor.decode(data.copyOfRange(37, data.size)) as Map<*, *>
                    val secret = PasskeyBase64.decode(assertNotNull(handler.credential?.prfSecret))
                    assertContentEquals(
                        hmac(secret, browser.salts),
                        browser.decrypt(extensions["hmac-secret"] as ByteArray),
                    )
                    secret.fill(0)
                    assertEquals(null, handler.lastSalts?.second)
                } finally {
                    session.clear()
                }
            }
        }

    @Test
    fun rejectsTamperedAndMalformedPrfBeforeApproval() =
        runTest {
            val handler = Authenticator()
            val session = HybridCtapSession(ByteArray(16))
            try {
                val browser = Browser(2)
                browser.connect(session, handler)
                val input = browser.input()
                val tag = (input[3] as ByteArray).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                assertEquals(
                    HybridCtap.PIN_AUTH_INVALID,
                    session.handle(assertion(input + (3 to tag)), handler).bytes[0].toInt(),
                )
                assertEquals(
                    HybridCtap.INVALID_PARAMETER,
                    session.handle(assertion(input + (4 to 3)), handler).bytes[0].toInt(),
                )
                val peer = (input[1] as Map<*, *>).entries.associate { it.key to it.value } + (-2 to ByteArray(32))
                assertEquals(
                    HybridCtap.INVALID_PARAMETER,
                    session.handle(assertion(input + (1 to peer)), handler).bytes[0].toInt(),
                )
                assertEquals(0, handler.calls)
            } finally {
                session.clear()
            }
        }

    @Test
    fun registrationProbesAndUnsupportedInputsNeverCreate() =
        runTest {
            val handler = Authenticator()
            val session = HybridCtapSession(ByteArray(16))
            try {
                val command = byteArrayOf(1) + HybridCbor.encode(mapOf(2 to mapOf("id" to ".dummy")))
                val probe = session.handle(command, handler)
                assertFalse(probe.finished)
                assertEquals(HybridCtap.PIN_AUTH_INVALID, probe.bytes[0].toInt())
                val missing = session.handle(registration(mapOf<Int, Any>(), enable = false), handler)
                assertEquals(HybridCtap.MISSING_PARAMETER, missing.bytes[0].toInt())
                val unsupported = session.handle(registration(null, algorithm = -8), handler)
                assertEquals(HybridCtap.UNSUPPORTED_ALGORITHM, unsupported.bytes[0].toInt())
                assertEquals(0, handler.calls)
            } finally {
                session.clear()
            }
        }

    private class Authenticator : HybridAuthenticator {
        val engine = WebAuthnAuthenticator(Json, WebAuthnAuthenticatorDataFactory(ByteArray(16)))
        var credential: WebAuthnCredential? = null
        var lastSalts: WebAuthnPrfValues? = null
        var calls = 0

        override suspend fun getAssertion(request: HybridAssertionRequest): HybridResult<WebAuthnAssertionResult> {
            calls++
            lastSalts = request.options.prf
            val result = engine.getAssertion(request.options, requireNotNull(credential), true, request::extensions)
            return HybridResult.Success(result)
        }

        override suspend fun makeCredential(request: HybridRegistrationRequest):
            HybridResult<WebAuthnCredentialCreationResult> {
            calls++
            val result = engine.createCredential(request.options, true, authenticatorExtensions = request::extensions)
            credential = result.credential
            return HybridResult.Success(result)
        }
    }

    private class Browser(
        private val protocol: Int,
        twoInputs: Boolean = true,
    ) {
        private var keys = byteArrayOf()
        private val pair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val salts =
            sha256("WebAuthn PRF".encodeToByteArray() + 0 + INPUT_ONE) +
                (if (twoInputs) sha256("WebAuthn PRF".encodeToByteArray() + 0 + INPUT_TWO) else byteArrayOf())

        suspend fun connect(
            session: HybridCtapSession,
            authenticator: HybridAuthenticator,
        ) {
            val command = byteArrayOf(6) + HybridCbor.encode(mapOf(1 to protocol, 2 to 2))
            val response = session.handle(command, authenticator)
            assertEquals(0, response.bytes[0].toInt())
            val peer = body(response.bytes)[1L] as Map<*, *>
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private)
            agreement.doPhase(publicKey(peer), true)
            val shared = agreement.generateSecret()
            keys =
                if (protocol == 1) {
                    sha256(shared).let { it + it }
                } else {
                    hkdf(shared, "CTAP2 HMAC key") + hkdf(shared, "CTAP2 AES key")
                }
        }

        fun input(): Map<Int, Any> {
            val public = pair.public as ECPublicKey
            val iv = if (protocol == 1) ByteArray(16) else ByteArray(16) { 3 }
            val encrypted = cbc(true, keys.copyOfRange(32, 64), iv, salts)
            val encoded = if (protocol == 1) encrypted else iv + encrypted
            val tag = hmac(keys.copyOf(32), encoded).copyOf(if (protocol == 1) 16 else 32)
            return mapOf(
                1 to
                    mapOf(
                        1 to 2,
                        3 to -25,
                        -1 to 1,
                        -2 to coordinate(public.w.affineX),
                        -3 to coordinate(public.w.affineY),
                    ),
                2 to encoded,
                3 to tag,
                4 to protocol,
            )
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val iv = if (protocol == 1) ByteArray(16) else ciphertext.copyOf(16)
            val payload = if (protocol == 1) ciphertext else ciphertext.copyOfRange(16, ciphertext.size)
            return cbc(false, keys.copyOfRange(32, 64), iv, payload)
        }
    }

    private companion object {
        val INPUT_ONE = "first input".encodeToByteArray()
        val INPUT_TWO = "second input".encodeToByteArray()

        fun body(response: ByteArray) = HybridCbor.decode(response.copyOfRange(1, response.size)) as Map<*, *>

        fun registration(
            input: Map<Int, Any>?,
            enable: Boolean = true,
            algorithm: Int = -7,
        ): ByteArray {
            val extensions = linkedMapOf<String, Any>("hmac-secret" to enable)
            input?.let { extensions["hmac-secret-mc"] = it }
            return byteArrayOf(1) +
                HybridCbor.encode(
                    mapOf(
                        1 to ByteArray(32) { 6 },
                        2 to mapOf("id" to "example.com", "name" to "Example"),
                        3 to mapOf("id" to byteArrayOf(1), "name" to "alice"),
                        4 to listOf(mapOf("type" to "public-key", "alg" to algorithm)),
                        6 to extensions,
                        7 to mapOf("rk" to true, "uv" to true),
                    ),
                )
        }

        fun assertion(input: Map<Int, Any>): ByteArray =
            byteArrayOf(2) +
                HybridCbor.encode(
                    mapOf(
                        1 to "example.com",
                        2 to ByteArray(32) { 8 },
                        4 to mapOf("hmac-secret" to input),
                        5 to mapOf("uv" to true),
                    ),
                )

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        fun hkdf(
            input: ByteArray,
            label: String,
        ): ByteArray = hmac(hmac(ByteArray(32), input), label.encodeToByteArray() + 1)

        fun coordinate(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size >= 32) {
                bytes.copyOfRange(bytes.size - 32, bytes.size)
            } else {
                ByteArray(32 - bytes.size) + bytes
            }
        }

        fun publicKey(cose: Map<*, *>): java.security.PublicKey {
            val parameters =
                AlgorithmParameters
                    .getInstance("EC")
                    .apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, cose[-2L] as ByteArray), BigInteger(1, cose[-3L] as ByteArray))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        }

        fun verify(
            cose: Map<*, *>,
            data: ByteArray,
            signature: ByteArray,
        ): Boolean =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey(cose))
                update(data)
                verify(signature)
            }

        fun cbc(
            encrypt: Boolean,
            key: ByteArray,
            iv: ByteArray,
            input: ByteArray,
        ): ByteArray =
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(
                    if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(iv),
                )
                doFinal(input)
            }
    }
}
