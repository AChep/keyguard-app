package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyError
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyPublicKey
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAuthenticatorSelection
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPublicKeyCredentialDescriptor
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAuthnAuthenticatorTest {
    @Test
    fun `registration preserves response fields and clears generated key buffers`() {
        val fixture = Fixture()
        val result = fixture.authenticator.createCredential(
            data = creationOptions(),
            context = caller,
            userVerified = true,
            transports = listOf("internal", "usb"),
        )
        val credential = result.credential
        assertEquals(credentialId, credential.credentialId)
        assertEquals("AQID", credential.keyValue)
        assertEquals("ECDSA", credential.keyAlgorithm)
        assertEquals("P-256", credential.keyCurve)
        assertEquals("example.com", credential.rpId)
        assertEquals("dXNlcg", credential.userHandle)
        assertEquals(0, credential.counter)
        assertTrue(credential.discoverable)
        assertFalse(credential.toString().contains(credential.keyValue))

        val body = Json.parseToJsonElement(result.responseJson).jsonObject
        assertEquals(body["id"], body["rawId"])
        assertEquals("cross-platform", body.getValue("authenticatorAttachment").jsonPrimitive.content)
        assertEquals("public-key", body.getValue("type").jsonPrimitive.content)
        val response = body.getValue("response").jsonObject
        assertEquals(
            listOf("internal", "usb"),
            response.getValue("transports").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("-7", response.getValue("publicKeyAlgorithm").jsonPrimitive.content)
        assertEquals("BAUG", response.getValue("publicKey").jsonPrimitive.content)
        val clientData = Json.parseToJsonElement(response.decodeBytes("clientDataJSON").decodeToString()).jsonObject
        assertEquals("webauthn.create", clientData.getValue("type").jsonPrimitive.content)
        assertEquals("YQ", clientData.getValue("challenge").jsonPrimitive.content)
        assertEquals(caller.origin, clientData.getValue("origin").jsonPrimitive.content)
        assertEquals(caller.androidPackageName, clientData.getValue("androidPackageName").jsonPrimitive.content)

        val authData = response.decodeBytes("authenticatorData")
        assertEquals(0x5d, authData[32].toInt())
        assertContentEquals(ByteArray(4), authData.copyOfRange(33, 37))
        assertContentEquals(ByteArray(16) { 0x7f }, authData.copyOfRange(37, 53))
        assertContentEquals(PasskeyCredentialId.encode(credentialId), authData.copyOfRange(55, 71))
        assertContentEquals(webAuthnNoneAttestationObject(authData), response.decodeBytes("attestationObject"))
        fixture.crypto.generated.let { key ->
            assertTrue(key.privateKeyPkcs8.all { it == 0.toByte() })
            val publicKey = key.publicKey as PasskeyPublicKey.EcP256
            assertTrue((publicKey.x + publicKey.y + publicKey.spki).all { it == 0.toByte() })
        }
    }

    @Test
    fun `unsupported parameters and excluded credentials fail before key generation`() {
        val fixture = Fixture()
        assertFailsWith<WebAuthnNotSupportedException> {
            val options = creationOptions().copy(
                pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(-257.0, "public-key")),
            )
            fixture.create(options)
        }
        assertFailsWith<WebAuthnInvalidStateException> {
            fixture.authenticator.createCredential(
                data = creationOptions().copy(
                    excludeCredentials = listOf(
                        CreatePasskeyPublicKeyCredentialDescriptor(
                            "public-key",
                            PasskeyBase64.encodeToString(PasskeyCredentialId.encode(credentialId)),
                        ),
                    ),
                ),
                context = caller,
                userVerified = true,
                transports = listOf("internal"),
                credentials = listOf(credential()),
            )
        }
        assertEquals(0, fixture.crypto.generateCalls)
    }

    @Test
    fun `assertion signs authenticator data with supplied client hash and clears owned buffers`() {
        val fixture = Fixture()
        val clientHash = ByteArray(32) { 0x33 }
        val body = Json.parseToJsonElement(fixture.assertion(credential(counter = 7), clientHash)).jsonObject
        val response = body.getValue("response").jsonObject
        val authData = response.decodeBytes("authenticatorData")
        assertEquals(37, authData.size)
        assertEquals(0x1d, authData[32].toInt())
        assertContentEquals(byteArrayOf(0, 0, 0, 7), authData.copyOfRange(33, 37))
        assertContentEquals(authData + clientHash, fixture.crypto.signedDataCopy)
        assertContentEquals(byteArrayOf(1, 2, 3), fixture.crypto.signedKeyCopy)
        assertEquals("MAA", response.getValue("signature").jsonPrimitive.content)
        assertEquals("dXNlcg", response.getValue("userHandle").jsonPrimitive.content)
        assertEquals(0, fixture.clientHashCalls)
        assertContentEquals(ByteArray(32) { 0x33 }, clientHash)
        fixture.assertCleared()
    }

    @Test
    fun `assertion hashes client data when caller supplies no hash`() {
        val fixture = Fixture()
        val body = Json.parseToJsonElement(fixture.assertion()).jsonObject
        val response = body.getValue("response").jsonObject
        assertEquals(1, fixture.clientHashCalls)
        assertContentEquals(response.decodeBytes("clientDataJSON"), fixture.hashedClientData)
        assertContentEquals(ByteArray(32) { 0x44 }, fixture.crypto.signedDataCopy.takeLast(32).toByteArray())
    }

    @Test
    fun `web callers omit android package and empty user handles`() {
        for (userHandle in listOf(null, "")) {
            val fixture = Fixture()
            val response = fixture.assertion(
                credential().copy(userHandle = userHandle),
                context = caller.copy(androidPackageName = null),
            ).webAuthnResponse()
            val clientData = Json.parseToJsonElement(response.decodeBytes("clientDataJSON").decodeToString()).jsonObject
            assertFalse("androidPackageName" in clientData)
            assertFalse("userHandle" in response)
        }
    }

    @Test
    fun `null zero and negative stored counters remain zero`() {
        for (counter in listOf(null, 0, -1)) {
            val response = Fixture().assertion(credential(counter)).webAuthnResponse()
            val authData = response.decodeBytes("authenticatorData")
            assertContentEquals(ByteArray(4), authData.copyOfRange(33, 37))
        }
    }

    @Test
    fun `key size and profile are checked before storage decoding`() {
        val unsupported = listOf(
            credential().copy(keyValue = "x".repeat(5_465)),
            credential().copy(keyCurve = "P-384"),
        )
        for (credential in unsupported) {
            val fixture = Fixture()
            assertFailsWith<WebAuthnEncodingException> { fixture.assertion(credential) }
            assertEquals(0, fixture.decodeCalls)
            assertEquals(0, fixture.crypto.signCalls)
        }
    }

    @Test
    fun `storage decoder failure is an encoding error`() {
        val fixture = Fixture(decodeFails = true)
        assertFailsWith<WebAuthnEncodingException> { fixture.assertion() }
        assertEquals(0, fixture.crypto.signCalls)
    }

    @Test
    fun `crypto errors and exceptions clear decoded keys and signing payload`() {
        for (throws in listOf(false, true)) {
            val fixture = Fixture()
            fixture.crypto.failSigning = true
            fixture.crypto.throwSigning = throws
            assertFailsWith<IllegalStateException> { fixture.assertion() }
            fixture.assertCleared()
        }
    }

    @Test
    fun `rp mismatch and non discoverable credential fail before storage decoding`() {
        val fixture = Fixture()
        assertFailsWith<WebAuthnNotAllowedException> {
            fixture.assertion(credential().copy(rpId = "different.example"))
        }
        assertFailsWith<WebAuthnNotAllowedException> { fixture.assertion(credential().copy(discoverable = false)) }
        assertEquals(0, fixture.decodeCalls)
        assertEquals(0, fixture.crypto.signCalls)
        assertEquals(0, fixture.clientHashCalls)
    }

    private class Fixture(decodeFails: Boolean = false) {
        val crypto = RecordingCrypto()
        var decodeCalls = 0
        var clientHashCalls = 0
        var hashedClientData = byteArrayOf()
        val authenticator = WebAuthnAuthenticator(
            json = Json,
            authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(
                ByteArray(16) { 0x7f },
                hashSha256 = { ByteArray(32) },
            ),
            decodeStoredPrivateKey = {
                decodeCalls++
                if (decodeFails) null else byteArrayOf(1, 2, 3)
            },
            passkeyCrypto = crypto,
            generateCredentialId = { credentialId },
            hashSha256 = {
                clientHashCalls++
                hashedClientData = it.copyOf()
                ByteArray(32) { 0x44 }
            },
        )

        fun create(options: CreatePasskey) =
            authenticator.createCredential(options, caller, true, listOf("internal"))

        fun assertion(
            credential: WebAuthnCredential = credential(),
            clientHash: ByteArray? = null,
            context: WebAuthnCallerContext = caller,
        ) = authenticator.getAssertion(
            WebAuthnAssertionRequest(
                byteArrayOf(0x61),
                "preferred",
                WebAuthnAllowedCredentialDescriptors(false, emptyList()),
            ),
            context,
            credential,
            userVerified = true,
            clientDataHash = clientHash,
        )

        fun assertCleared() {
            assertTrue(crypto.signedKey.all { it == 0.toByte() })
            assertTrue(crypto.signedData.all { it == 0.toByte() })
            assertTrue(crypto.signature.all { it == 0.toByte() })
        }
    }

    private class RecordingCrypto : PasskeyCrypto {
        override val supportedAlgorithms = setOf(PasskeySignatureAlgorithm.ES256)
        var generateCalls = 0
        var signCalls = 0
        var failSigning = false
        var throwSigning = false
        lateinit var generated: PasskeyKeyMaterial
        var signedKey = byteArrayOf()
        var signedData = byteArrayOf()
        var signedKeyCopy = byteArrayOf()
        var signedDataCopy = byteArrayOf()
        var signature = byteArrayOf()

        override fun generate(algorithm: PasskeySignatureAlgorithm): PasskeyKeyMaterial {
            generateCalls++
            return PasskeyKeyMaterial(
                PasskeyKeyProfile.EC_P256,
                byteArrayOf(1, 2, 3),
                PasskeyPublicKey.EcP256(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }, byteArrayOf(4, 5, 6)),
            ).also { generated = it }
        }

        override fun inspect(privateKeyPkcs8: ByteArray): PasskeyKeyInspectionResult = error("Unused")

        override fun sign(
            algorithm: PasskeySignatureAlgorithm,
            privateKeyPkcs8: ByteArray,
            data: ByteArray,
        ): PasskeySignResult {
            signCalls++
            signedKey = privateKeyPkcs8
            signedData = data
            signedKeyCopy = privateKeyPkcs8.copyOf()
            signedDataCopy = data.copyOf()
            if (throwSigning) error("Signing failed")
            if (failSigning) return PasskeySignResult.Error(PasskeyKeyError.MALFORMED)
            signature = byteArrayOf(0x30, 0)
            return PasskeySignResult.Success(signature)
        }
    }

    private companion object {
        const val credentialId = "123e4567-e89b-12d3-a456-426614174000"
        val caller = WebAuthnCallerContext("https://example.com", "example.com", "com.example.app")

        fun creationOptions() = CreatePasskey(
            challenge = "YQ",
            pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(-7.0, "public-key")),
            rp = CreatePasskeyRelyingParty("example.com", "Example"),
            user = CreatePasskeyUser("dXNlcg", "alice", "Alice"),
            authenticatorSelection = CreatePasskeyAuthenticatorSelection(residentKey = "preferred"),
        )

        fun credential(counter: Int? = 0) = WebAuthnCredential(
            credentialId, "public-key", "ECDSA", "P-256", "AQID", "example.com", true,
            counter = counter,
            userHandle = "dXNlcg",
        )
    }
}
