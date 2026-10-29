package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAuthenticatorSelection
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyExtensions
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NativeWebAuthnPrfTest {
    /** https://www.w3.org/TR/webauthn-3/#sctn-test-vectors-prf */
    @Test
    fun `evaluates the W3C test vectors`() {
        val secret = "437e065e723a98b2f08f39d8baf7c53ecb3c363c5e5104bdaaf5d5ca2e028154".hexToByteArray()
        val first = "576562417574686e20505246207465737420766563746f727302".hexToByteArray()
        val second = "576562417574686e20505246207465737420766563746f727303".hexToByteArray()

        assertEquals(
            "3c33e07d202c3b029cc21f1722767021bf27d595933b3d2b6a1b9d5dddc77fae",
            webAuthnPrf(secret, first, ::hmacSha256, NativeCrypto.primitives::sha256).toHexString(),
        )
        assertEquals(
            "a62a8773b19cda90d7ed4ef72a80a804320dbd3997e2f663805ad1fd3293d50b",
            webAuthnPrf(secret, second, ::hmacSha256, NativeCrypto.primitives::sha256).toHexString(),
        )
    }

    @Test
    fun `registration and assertion derive the same outputs`() {
        val authenticator = WebAuthnAuthenticator(
            json = Json,
            authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(ByteArray(16)),
        )
        val caller = WebAuthnCallerContext("https://example.com", "example.com")
        val input = PasskeyBase64.encodeToString("salt".encodeToByteArray())
        val registration = authenticator.createCredential(
            data = CreatePasskey(
                challenge = "YQ",
                pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(-7.0, "public-key")),
                rp = CreatePasskeyRelyingParty("example.com", "Example"),
                user = CreatePasskeyUser("dXNlcg", "alice", "Alice"),
                authenticatorSelection = CreatePasskeyAuthenticatorSelection(residentKey = "required"),
                extensions = CreatePasskeyExtensions(
                    prf = Json.parseToJsonElement("""{"eval":{"first":"$input"}}"""),
                ),
            ),
            context = caller,
            userVerified = true,
            transports = listOf("internal"),
        )
        assertEquals(
            WEBAUTHN_PRF_SECRET_BYTES,
            PasskeyBase64.decode(requireNotNull(registration.credential.prfSecret)).size,
        )

        val assertion = authenticator.getAssertion(
            request = WebAuthnAssertionRequest(
                challenge = byteArrayOf(0x62),
                userVerification = "required",
                allowedCredentials = WebAuthnAllowedCredentialDescriptors.NONE,
                prf = WebAuthnPrfAssertionInputs(eval = WebAuthnPrfValues(PasskeyBase64.decode(input))),
            ),
            context = caller,
            credential = registration.credential,
            userVerified = true,
        )

        assertContentEquals(
            registration.responseJson.prfResults().decodeBytes("first"),
            assertion.prfResults().decodeBytes("first"),
        )
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray) =
        NativeCrypto.primitives.hmac(key, data, NativeHashAlgorithm.SHA_256)

    private fun String.prfResults(): JsonObject = Json.parseToJsonElement(this).jsonObject
        .getValue("clientExtensionResults").jsonObject
        .getValue("prf").jsonObject
        .getValue("results").jsonObject
}
