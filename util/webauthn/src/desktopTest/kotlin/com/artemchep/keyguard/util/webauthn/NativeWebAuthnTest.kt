package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAuthenticatorSelection
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class NativeWebAuthnTest {
    @Test
    fun `native registration key produces an independently verified assertion`() {
        val authenticator = WebAuthnAuthenticator(
            json = Json,
            authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(ByteArray(16)),
            decodeStoredPrivateKey = PasskeyBase64::decode,
        )
        val caller = WebAuthnCallerContext("https://example.com", "example.com")
        val registration = authenticator.createCredential(
            data = CreatePasskey(
                challenge = "YQ",
                pubKeyCredParams = emptyList(),
                rp = CreatePasskeyRelyingParty("example.com", "Example"),
                user = CreatePasskeyUser("dXNlcg", "alice", "Alice"),
                authenticatorSelection = CreatePasskeyAuthenticatorSelection(residentKey = "required"),
            ),
            context = caller,
            userVerified = true,
            transports = listOf("internal"),
        )
        val registeredResponse = registration.responseJson.webAuthnResponse()
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(PasskeyBase64.decode(registeredResponse.getValue("publicKey").jsonPrimitive.content)),
        )
        val assertion = authenticator.getAssertion(
            request = WebAuthnAssertionRequest(
                challenge = byteArrayOf(0x62),
                userVerification = "required",
                allowedCredentials = WebAuthnAllowedCredentialDescriptors(false, emptyList()),
            ),
            context = caller,
            credential = registration.credential,
            userVerified = true,
        )
        val response = assertion.webAuthnResponse()
        val authData = PasskeyBase64.decode(response.getValue("authenticatorData").jsonPrimitive.content)
        val clientData = PasskeyBase64.decode(response.getValue("clientDataJSON").jsonPrimitive.content)
        val signature = PasskeyBase64.decode(response.getValue("signature").jsonPrimitive.content)
        val sha256 = MessageDigest.getInstance("SHA-256")
        assertContentEquals(sha256.digest("example.com".encodeToByteArray()), authData.copyOfRange(0, 32))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(authData + sha256.digest(clientData))
        assertTrue(verifier.verify(signature))
    }
}
