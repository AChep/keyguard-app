package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyPublicKey
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAuthenticatorSelection
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyExtensions
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAuthnPrfTest {
    @Test
    fun `registration that requests the PRF stores a secret and evaluates the inputs`() {
        val fixture = Fixture()
        val result = fixture.create(prf = """{"eval":{"first":"$INPUT_A","second":"$INPUT_B"}}""")

        assertEquals(PasskeyBase64.encodeToString(SECRET), result.credential.prfSecret)
        val prf = result.responseJson.clientExtensionResults().getValue("prf").jsonObject
        assertEquals("true", prf.getValue("enabled").jsonPrimitive.content)
        val results = prf.getValue("results").jsonObject
        assertContentEquals(expectedPrf(INPUT_A), results.decodeBytes("first"))
        assertContentEquals(expectedPrf(INPUT_B), results.decodeBytes("second"))
        assertTrue(fixture.generatedSecrets.single().all { it == 0.toByte() })
    }

    @Test
    fun `registration without inputs only reports support`() {
        val fixture = Fixture()
        val result = fixture.create(prf = "{}")

        assertEquals(PasskeyBase64.encodeToString(SECRET), result.credential.prfSecret)
        val prf = result.responseJson.clientExtensionResults().getValue("prf").jsonObject
        assertEquals(setOf("enabled"), prf.keys)
    }

    @Test
    fun `registration that does not request the PRF stores a secret without extension output`() {
        val fixture = Fixture()
        val result = fixture.create(prf = null)

        assertEquals(PasskeyBase64.encodeToString(SECRET), result.credential.prfSecret)
        assertTrue(result.responseJson.clientExtensionResults().isEmpty())
        assertTrue(fixture.generatedSecrets.single().all { it == 0.toByte() })
    }

    @Test
    fun `credential registered without PRF can evaluate it later`() {
        val fixture = Fixture()
        val registration = fixture.authenticator.createCredential(
            request = createPasskey(prf = null).toRegistrationRequest(caller.rpId),
            userVerified = true,
        )

        assertFalse(registration.prfEnabled)
        assertNull(registration.prfResults)
        repeat(2) {
            val response = fixture.assertion(
                credential = registration.credential,
                prf = WebAuthnPrfAssertionInputs(eval = WebAuthnPrfValues(PasskeyBase64.decode(INPUT_A))),
            )
            val results = response.clientExtensionResults()
                .getValue("prf").jsonObject
                .getValue("results").jsonObject
            assertContentEquals(expectedPrf(INPUT_A), results.decodeBytes("first"))
        }
        assertEquals(1, fixture.generatedSecrets.size)
    }

    // WebAuthn L3 §10.1.4: the choice of PRF "MUST be independent of the state
    // of user verification", and "discouraged" reports an unverified user.
    @Test
    fun `registration without user verification evaluates the same PRF`() {
        val fixture = Fixture()
        val result = fixture.create(
            prf = """{"eval":{"first":"$INPUT_A"}}""",
            userVerification = "discouraged",
        )

        assertEquals(PasskeyBase64.encodeToString(SECRET), result.credential.prfSecret)
        val results = result.responseJson.clientExtensionResults()
            .getValue("prf").jsonObject
            .getValue("results").jsonObject
        assertContentEquals(expectedPrf(INPUT_A), results.decodeBytes("first"))
    }

    @Test
    fun `assertion without user verification evaluates the same PRF`() {
        listOf(
            "discouraged" to true,
            "preferred" to false,
        ).forEach { (userVerification, userVerified) ->
            val response = Fixture().assertion(
                prf = WebAuthnPrfAssertionInputs(eval = WebAuthnPrfValues(PasskeyBase64.decode(INPUT_A))),
                userVerification = userVerification,
                userVerified = userVerified,
            )

            val results = response.clientExtensionResults()
                .getValue("prf").jsonObject
                .getValue("results").jsonObject
            assertContentEquals(expectedPrf(INPUT_A), results.decodeBytes("first"), userVerification)
        }
    }

    // WebAuthn L3 §10.1.4: "If evalByCredential is present, return a
    // DOMException whose name is NotSupportedError", even when it is empty.
    @Test
    fun `registration with inputs by credential is not supported`() {
        listOf(
            "{}",
            """{"AQID":{"first":"$INPUT_A"}}""",
        ).forEach { evalByCredential ->
            val fixture = Fixture()
            assertFailsWith<WebAuthnNotSupportedException> {
                fixture.create(prf = """{"evalByCredential":$evalByCredential}""")
            }
            assertTrue(fixture.generatedSecrets.isEmpty())
        }
    }

    @Test
    fun `registration rejects malformed inputs before anything is created`() {
        listOf(
            "true",
            """{"eval":"$INPUT_A"}""",
            """{"eval":{}}""",
            """{"eval":{"first":5}}""",
            """{"eval":{"first":"+/"}}""",
            """{"eval":{"first":"$INPUT_A","second":5}}""",
        ).forEach { prf ->
            val data = createPasskey(prf)
            assertFailsWith<WebAuthnEncodingException>(prf) {
                data.requireWebAuthnPrfRegistrationInputs()
            }
        }
    }

    @Test
    fun `registration reads null inputs as absent`() {
        assertNull(createPasskey("null").toPrfRegistrationInputOrNull())
        val input = assertNotNull(
            createPasskey("""{"eval":null,"evalByCredential":null}""").toPrfRegistrationInputOrNull(),
        )
        assertNull(input.eval)
        val values = assertNotNull(
            createPasskey("""{"eval":{"first":"$INPUT_A","second":null}}""").toPrfRegistrationInputOrNull()?.eval,
        )
        assertNull(values.second)
    }

    @Test
    fun `registration keeps inputs by credential that a lenient parser reads`() {
        val data = Json { ignoreUnknownKeys = true }.decodeFromString<CreatePasskey>(
            """
                {
                  "challenge": "YQ",
                  "rp": { "id": "example.com", "name": "Example" },
                  "user": { "id": "dXNlcg", "name": "alice", "displayName": "Alice" },
                  "pubKeyCredParams": [{ "type": "public-key", "alg": -7 }],
                  "extensions": { "prf": { "evalByCredential": {}, "unknown": 1 } }
                }
            """.trimIndent(),
        )

        assertFailsWith<WebAuthnNotSupportedException> {
            data.requireWebAuthnPrfRegistrationInputs()
        }
    }

    @Test
    fun `assertion prefers the inputs of the asserted credential`() {
        val fixture = Fixture()
        val credentialIdKey = PasskeyBase64.encodeToString(PasskeyCredentialId.encode(CREDENTIAL_ID))
        val response = fixture.assertion(
            prf = WebAuthnPrfAssertionInputs(
                eval = WebAuthnPrfValues(PasskeyBase64.decode(INPUT_A)),
                evalByCredential = mapOf(credentialIdKey to WebAuthnPrfValues(PasskeyBase64.decode(INPUT_B))),
            ),
        )

        val results = response.clientExtensionResults()
            .getValue("prf").jsonObject
            .getValue("results").jsonObject
        assertContentEquals(expectedPrf(INPUT_B), results.decodeBytes("first"))
        assertFalse("second" in results)
    }

    @Test
    fun `assertion of a credential without a secret returns an empty output`() {
        val response = Fixture().assertion(
            credential = credential().copy(prfSecret = null),
            prf = WebAuthnPrfAssertionInputs(eval = WebAuthnPrfValues(PasskeyBase64.decode(INPUT_A))),
        )

        val prf = response.clientExtensionResults().getValue("prf").jsonObject
        assertTrue(prf.isEmpty())
    }

    @Test
    fun `assertion that does not request the PRF has no output`() {
        val response = Fixture().assertion(prf = null)

        assertTrue(response.clientExtensionResults().isEmpty())
    }

    @Test
    fun `binary assertion returns caller owned outputs`() {
        val fixture = Fixture()
        val result = fixture.authenticator.getAssertion(
            request = WebAuthnAssertionHashRequest(
                rpId = caller.rpId,
                clientDataHash = ByteArray(32),
                userVerification = "required",
                allowedCredentials = WebAuthnAllowedCredentialDescriptors.NONE,
                prf = WebAuthnPrfValues(PasskeyBase64.decode(INPUT_A)),
            ),
            credential = credential(),
            userVerified = true,
        )

        assertContentEquals(expectedPrf(INPUT_A), result.prfResults?.first)
        assertNull(result.prfResults?.second)
    }

    @Test
    fun `salts that a platform derived are evaluated as they are`() {
        val fixture = Fixture()
        val salt = ByteArray(32) { 7 }
        val assertion = fixture.authenticator.getAssertion(
            request = WebAuthnAssertionHashRequest(
                rpId = caller.rpId,
                clientDataHash = ByteArray(32),
                userVerification = "required",
                allowedCredentials = WebAuthnAllowedCredentialDescriptors.NONE,
                prf = WebAuthnPrfValues(salt),
                prfSalted = true,
            ),
            credential = credential(),
            userVerified = true,
        )
        assertContentEquals(fakeHmacSha256(SECRET, salt), assertion.prfResults?.first)

        val registration = fixture.authenticator.createCredential(
            request = WebAuthnRegistrationRequest(
                rpId = caller.rpId,
                userHandle = "dXNlcg",
                pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(-7.0, "public-key")),
                discoverable = true,
                prf = WebAuthnPrfRegistrationInput(eval = WebAuthnPrfValues(salt), salted = true),
            ),
            userVerified = true,
        )
        assertTrue(registration.prfEnabled)
        assertContentEquals(fakeHmacSha256(SECRET, salt), registration.prfResults?.first)
    }

    @Test
    fun `parses the assertion inputs of a request`() {
        val credentialIdBytes = byteArrayOf(1, 2, 3)
        val inputs = parsePrf(
            requestJson = """
                {
                  "challenge": "YQ",
                  "extensions": {
                    "prf": {
                      "eval": { "first": "$INPUT_A", "second": "$INPUT_B" },
                      "evalByCredential": { "AQID": { "first": "$INPUT_B" } }
                    }
                  }
                }
            """.trimIndent(),
            allowedCredentials = WebAuthnAllowedCredentialDescriptors.fromCredentialIds(listOf(credentialIdBytes)),
        )

        assertContentEquals(PasskeyBase64.decode(INPUT_A), inputs?.eval?.first)
        assertContentEquals(PasskeyBase64.decode(INPUT_B), inputs?.eval?.second)
        assertContentEquals(PasskeyBase64.decode(INPUT_B), inputs?.valuesFor(credentialIdBytes)?.first)
        assertContentEquals(PasskeyBase64.decode(INPUT_A), inputs?.valuesFor(byteArrayOf(4))?.first)
    }

    @Test
    fun `parsing returns null without the PRF and rejects malformed inputs`() {
        val none = WebAuthnAllowedCredentialDescriptors.NONE
        assertNull(parsePrf("""{"challenge":"YQ","extensions":{}}""", none))
        assertNull(parsePrf("""{"extensions":{"prf":null}}""", none))
        listOf(
            "true",
            """{"eval":"$INPUT_A"}""",
            """{"eval":{}}""",
            """{"eval":{"first":"+/"}}""",
            """{"eval":{"first":"$INPUT_A","second":5}}""",
            """{"evalByCredential":"AQID"}""",
            """{"evalByCredential":{"AQID":null}}""",
        ).forEach { prf ->
            assertFailsWith<WebAuthnEncodingException>(prf) {
                parsePrf("""{"extensions":{"prf":$prf}}""", allowedCredentials)
            }
        }
    }

    @Test
    fun `a failed PRF evaluation wipes the signature`() {
        val signatures = mutableListOf<ByteArray>()
        val fixture = Fixture(
            onSign = signatures::add,
            hmacSha256 = { _, _ -> error("HMAC failed") },
        )
        assertFailsWith<IllegalStateException> {
            fixture.assertion(prf = WebAuthnPrfAssertionInputs(eval = WebAuthnPrfValues(PasskeyBase64.decode(INPUT_A))))
        }
        assertTrue(signatures.single().all { it == 0.toByte() })
    }

    // WebAuthn L3 §10.1.4, client extension processing (authentication).
    @Test
    fun `inputs by credential require an allow list`() {
        assertFailsWith<WebAuthnNotSupportedException> {
            parseEvalByCredential(""""AQID": { "first": "$INPUT_A" }""", WebAuthnAllowedCredentialDescriptors.NONE)
        }
        assertNotNull(parseEvalByCredential("", WebAuthnAllowedCredentialDescriptors.NONE))
    }

    @Test
    fun `inputs by credential must name an allowed credential`() {
        listOf("", "+/+/", "BAUG").forEach { key ->
            assertFailsWith<WebAuthnSyntaxException>(key) {
                parseEvalByCredential(""""$key": { "first": "$INPUT_A" }""", allowedCredentials)
            }
        }
    }

    @Test
    fun `malformed values fail before the inputs by credential are checked`() {
        assertFailsWith<WebAuthnEncodingException> {
            parseEvalByCredential(""""AQID": { "first": "+/" }""", WebAuthnAllowedCredentialDescriptors.NONE)
        }
    }

    private val allowedCredentials = WebAuthnAllowedCredentialDescriptors.fromCredentialIds(
        listOf(byteArrayOf(1, 2, 3)),
    )

    private fun parseEvalByCredential(
        entries: String,
        allowedCredentials: WebAuthnAllowedCredentialDescriptors,
    ) = parsePrf(
        requestJson = """{"extensions":{"prf":{"evalByCredential":{$entries}}}}""",
        allowedCredentials = allowedCredentials,
    )

    private fun parsePrf(
        requestJson: String,
        allowedCredentials: WebAuthnAllowedCredentialDescriptors,
    ) = parseWebAuthnPrfAssertionInputs(
        body = Json.parseToJsonElement(requestJson).jsonObject,
        allowedCredentials = allowedCredentials,
    )

    private class Fixture(
        onSign: (ByteArray) -> Unit = {},
        hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray = ::fakeHmacSha256,
    ) {
        val generatedSecrets = mutableListOf<ByteArray>()
        val authenticator = WebAuthnAuthenticator(
            json = Json,
            authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(
                ByteArray(16),
                hashSha256 = { ByteArray(32) },
            ),
            decodeStoredPrivateKey = { byteArrayOf(1, 2, 3) },
            passkeyCrypto = FakePasskeyCrypto(onSign),
            generateCredentialId = { CREDENTIAL_ID },
            hashSha256 = ::fakeSha256,
            hmacSha256 = hmacSha256,
            randomBytes = { length ->
                assertEquals(WEBAUTHN_PRF_SECRET_BYTES, length)
                SECRET.copyOf().also(generatedSecrets::add)
            },
        )

        fun create(
            prf: String?,
            userVerification: String = "required",
        ) = authenticator.createCredential(
            data = createPasskey(prf, userVerification),
            context = caller,
            userVerified = true,
            transports = listOf("internal"),
        )

        fun assertion(
            credential: WebAuthnCredential = credential(),
            prf: WebAuthnPrfAssertionInputs?,
            userVerification: String = "required",
            userVerified: Boolean = true,
        ) = authenticator.getAssertion(
            request = WebAuthnAssertionRequest(
                challenge = byteArrayOf(0x61),
                userVerification = userVerification,
                allowedCredentials = WebAuthnAllowedCredentialDescriptors.NONE,
                prf = prf,
            ),
            context = caller,
            credential = credential,
            userVerified = userVerified,
        )
    }

    private class FakePasskeyCrypto(
        private val onSign: (ByteArray) -> Unit,
    ) : PasskeyCrypto {
        override val supportedAlgorithms = setOf(PasskeySignatureAlgorithm.ES256)

        override fun generate(algorithm: PasskeySignatureAlgorithm) = PasskeyKeyMaterial(
            PasskeyKeyProfile.EC_P256,
            byteArrayOf(1, 2, 3),
            PasskeyPublicKey.EcP256(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }, byteArrayOf(4, 5, 6)),
        )

        override fun inspect(privateKeyPkcs8: ByteArray): PasskeyKeyInspectionResult = error("Unused")

        override fun sign(
            algorithm: PasskeySignatureAlgorithm,
            privateKeyPkcs8: ByteArray,
            data: ByteArray,
        ): PasskeySignResult = PasskeySignResult.Success(byteArrayOf(0x30, 0).also(onSign))
    }

    private companion object {
        const val CREDENTIAL_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val INPUT_A = "YQ"
        const val INPUT_B = "Yg"

        val SECRET = ByteArray(WEBAUTHN_PRF_SECRET_BYTES) { 0x5a }
        val caller = WebAuthnCallerContext("https://example.com", "example.com")

        fun fakeSha256(data: ByteArray): ByteArray = byteArrayOf(0x48) + data

        fun fakeHmacSha256(key: ByteArray, data: ByteArray): ByteArray = key + data

        /** HMAC(secret, SHA-256("WebAuthn PRF" || 0x00 || input)) with the fakes above. */
        fun expectedPrf(input: String): ByteArray = fakeHmacSha256(
            key = SECRET,
            data = fakeSha256("WebAuthn PRF".encodeToByteArray() + 0 + PasskeyBase64.decode(input)),
        )

        /** Registration options whose PRF inputs are the [prf] JSON, if any. */
        fun createPasskey(
            prf: String?,
            userVerification: String = "required",
        ) = CreatePasskey(
            challenge = "YQ",
            pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(-7.0, "public-key")),
            rp = CreatePasskeyRelyingParty("example.com", "Example"),
            user = CreatePasskeyUser("dXNlcg", "alice", "Alice"),
            authenticatorSelection = CreatePasskeyAuthenticatorSelection(
                residentKey = "required",
                userVerification = userVerification,
            ),
            extensions = CreatePasskeyExtensions(prf = prf?.let(Json::parseToJsonElement)),
        )

        fun credential() = WebAuthnCredential(
            CREDENTIAL_ID, "public-key", "ECDSA", "P-256", "AQID", "example.com", true,
            userHandle = "dXNlcg",
            prfSecret = PasskeyBase64.encodeToString(SECRET),
        )

        fun String.clientExtensionResults(): JsonObject = Json.parseToJsonElement(this)
            .jsonObject.getValue("clientExtensionResults").jsonObject
    }
}
