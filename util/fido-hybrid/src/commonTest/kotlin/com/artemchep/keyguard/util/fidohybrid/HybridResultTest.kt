package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnPrfValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HybridResultTest {
    @Test
    fun typedFailuresMapToWireStatuses() =
        runTest {
            val session = HybridCtapSession(ByteArray(16))
            try {
                for ((result, status) in listOf(
                    HybridResult.Denied to HybridCtap.DENIED,
                    HybridResult.NoCredentials to HybridCtap.NO_CREDENTIALS,
                    HybridResult.CredentialExcluded to HybridCtap.CREDENTIAL_EXCLUDED,
                )) {
                    val response = session.handle(assertion(), Authenticator(result))
                    assertContentEquals(byteArrayOf(status.toByte()), response.bytes)
                    assertTrue(response.finished)
                }
            } finally {
                session.clear()
            }
        }

    @Test
    fun protocolOwnsResponseEncodingAndClearsTransferredBuffers() =
        runTest {
            val signature = byteArrayOf(0x30, 1)
            val prf = WebAuthnPrfValues(ByteArray(32) { 7 }, ByteArray(32) { 8 })
            val value = WebAuthnAssertionResult(byteArrayOf(1), ByteArray(37), signature, byteArrayOf(2), prf)
            val session = HybridCtapSession(ByteArray(16))
            try {
                val response = session.handle(assertion(), Authenticator(HybridResult.Success(value)))
                assertEquals(0, response.bytes[0].toInt())
                val encoded = HybridCbor.decode(response.bytes.copyOfRange(1, response.bytes.size)) as Map<*, *>
                assertContentEquals(byteArrayOf(0x30, 1), encoded[3L] as ByteArray)
                assertContentEquals(byteArrayOf(2), (encoded[4L] as Map<*, *>)["id"] as ByteArray)
                // The user picked the account on the phone for a request without an allow list.
                assertEquals(true, encoded[6L])
                assertTrue(signature.all { it == 0.toByte() })
                assertTrue(prf.first.all { it == 0.toByte() })
                assertTrue(requireNotNull(prf.second).all { it == 0.toByte() })
            } finally {
                session.clear()
            }
        }

    @Test
    fun userEntityNamesTheAccountOnlyWhenTheAuthenticatorDid() =
        runTest {
            val session = HybridCtapSession(ByteArray(16))
            try {
                for ((value, expected) in listOf(
                    WebAuthnAssertionResult(
                        byteArrayOf(1),
                        ByteArray(37),
                        byteArrayOf(0x30, 1),
                        byteArrayOf(2),
                        userName = "alice",
                        userDisplayName = "",
                    ) to mapOf("name" to "alice", "displayName" to ""),
                    WebAuthnAssertionResult(byteArrayOf(1), ByteArray(37), byteArrayOf(0x30, 1), byteArrayOf(2))
                        to emptyMap<String, String>(),
                )) {
                    val response = session.handle(assertion(), Authenticator(HybridResult.Success(value)))
                    val encoded = HybridCbor.decode(response.bytes.copyOfRange(1, response.bytes.size)) as Map<*, *>
                    val user = encoded[4L] as Map<*, *>
                    assertContentEquals(byteArrayOf(2), user["id"] as ByteArray)
                    assertEquals<Map<*, *>>(expected, user - "id")
                }
            } finally {
                session.clear()
            }
        }

    @Test
    fun credentialWithoutUserHandleOmitsUser() =
        runTest {
            val value = WebAuthnAssertionResult(byteArrayOf(1), ByteArray(37), byteArrayOf(0x30, 1), null)
            val session = HybridCtapSession(ByteArray(16))
            try {
                val response = session.handle(assertion(), Authenticator(HybridResult.Success(value)))
                assertEquals(0, response.bytes[0].toInt())
                val encoded = HybridCbor.decode(response.bytes.copyOfRange(1, response.bytes.size)) as Map<*, *>
                assertNull(encoded[4L])
            } finally {
                session.clear()
            }
        }

    @Test
    fun silentPreflightNeverEndsTheTransaction() =
        runTest {
            val value = WebAuthnAssertionResult(byteArrayOf(1), ByteArray(37), byteArrayOf(0x30, 1), byteArrayOf(2))
            val requests = mutableListOf<HybridAssertionRequest>()
            val authenticator =
                object : HybridAuthenticator {
                    override suspend fun getAssertion(request: HybridAssertionRequest) =
                        HybridResult.Success(value).also { requests += request }

                    override suspend fun makeCredential(request: HybridRegistrationRequest) = HybridResult.Denied
                }
            val allow = 3 to listOf(mapOf("type" to "public-key", "id" to byteArrayOf(1)))
            val silent = 5 to mapOf("up" to false)
            val session = HybridCtapSession(ByteArray(16))
            try {
                val found = session.handle(assertion(allow, silent), authenticator)
                assertEquals(0, found.bytes[0].toInt())
                assertFalse(found.finished)
                assertNull((HybridCbor.decode(found.bytes.copyOfRange(1, found.bytes.size)) as Map<*, *>)[6L])
                assertFalse(requests.single().userPresent)
                assertEquals("discouraged", requests.single().options.userVerification)
                for ((extra, status) in listOf(
                    listOf(silent) to HybridCtap.NO_CREDENTIALS,
                    listOf(allow, silent, 4 to mapOf("hmac-secret" to emptyMap<Any, Any>())) to
                        HybridCtap.UNSUPPORTED_OPTION,
                )) {
                    val response = session.handle(assertion(*extra.toTypedArray()), authenticator)
                    assertContentEquals(byteArrayOf(status.toByte()), response.bytes)
                    assertFalse(response.finished)
                }
                assertEquals(1, requests.size)
            } finally {
                session.clear()
            }
        }

    @Test
    fun creationWithoutUserPresenceIsAnInvalidOption() =
        runTest {
            val command = registration(7 to mapOf("up" to false))
            val session = HybridCtapSession(ByteArray(16))
            try {
                val response = session.handle(command, Authenticator(HybridResult.Denied))
                assertContentEquals(byteArrayOf(HybridCtap.INVALID_OPTION.toByte()), response.bytes)
            } finally {
                session.clear()
            }
        }

    @Test
    fun clientPinOnlyAnswersKeyAgreement() =
        runTest {
            val session = HybridCtapSession(ByteArray(16))
            try {
                for ((parameters, status) in listOf(
                    mapOf(1 to 2, 2 to 5) to HybridCtap.INVALID_SUBCOMMAND,
                    mapOf(1 to 3, 2 to 2) to HybridCtap.INVALID_PARAMETER,
                    mapOf(2 to 2) to HybridCtap.MISSING_PARAMETER,
                )) {
                    val command = byteArrayOf(6) + HybridCbor.encode(parameters)
                    val response = session.handle(command, Authenticator(HybridResult.Denied))
                    assertContentEquals(byteArrayOf(status.toByte()), response.bytes)
                    assertFalse(response.finished)
                }
            } finally {
                session.clear()
            }
        }

    // A cancellation that is not from the caller's own job, e.g. a cancelled user prompt.
    @Test
    fun cancellationIsNeverACtapStatus() =
        runTest {
            val cancelled =
                object : HybridAuthenticator {
                    override suspend fun getAssertion(
                        request: HybridAssertionRequest,
                    ): HybridResult<WebAuthnAssertionResult> = CompletableDeferred<Nothing>().apply { cancel() }.await()

                    override suspend fun makeCredential(
                        request: HybridRegistrationRequest,
                    ): HybridResult<WebAuthnCredentialCreationResult> =
                        CompletableDeferred<Nothing>().apply { cancel() }.await()
                }
            val session = HybridCtapSession(ByteArray(16))
            try {
                assertFailsWith<CancellationException> { session.handle(assertion(), cancelled) }
                assertFailsWith<CancellationException> { session.handle(registration(), cancelled) }
            } finally {
                session.clear()
            }
        }

    @Test
    fun relayPolicyBuildsOnlyBoundedTunnelDestinations() {
        assertEquals(
            "wss://cable.ua5v.com/cable/new/${"0".repeat(32)}",
            HybridRelay.GOOGLE.newTunnelUrl("0".repeat(32)),
        )
        for (id in listOf("", "0".repeat(31), "g".repeat(32), "../".repeat(11))) {
            assertFailsWith<IllegalArgumentException> { HybridRelay.GOOGLE.newTunnelUrl(id) }
        }
    }

    private class Authenticator(
        private val result: HybridResult<WebAuthnAssertionResult>,
    ) : HybridAuthenticator {
        override suspend fun getAssertion(request: HybridAssertionRequest) = result

        override suspend fun makeCredential(
            request: HybridRegistrationRequest,
        ): HybridResult<WebAuthnCredentialCreationResult> =
            HybridResult.Denied
    }

    private fun assertion(vararg extra: Pair<Int, Any>) =
        byteArrayOf(2) +
            HybridCbor.encode(
                mapOf(1 to "example.com", 2 to ByteArray(32)) + extra,
            )

    private fun registration(vararg extra: Pair<Int, Any>) =
        byteArrayOf(1) +
            HybridCbor.encode(
                mapOf(
                    1 to ByteArray(32),
                    2 to mapOf("id" to "example.com"),
                    3 to mapOf("id" to byteArrayOf(1)),
                    4 to listOf(mapOf("alg" to -7, "type" to "public-key")),
                ) + extra,
            )
}
