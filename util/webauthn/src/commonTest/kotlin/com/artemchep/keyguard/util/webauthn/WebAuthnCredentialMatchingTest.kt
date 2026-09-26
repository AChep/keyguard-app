package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPublicKeyCredentialDescriptor
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyUser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebAuthnCredentialMatchingTest {
    private val credential = WebAuthnCredential(
        credentialId = "123e4567-e89b-12d3-a456-426614174000",
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "AQID",
        rpId = "example.com",
        discoverable = true,
    )
    private val encodedId = PasskeyBase64.encodeToString(PasskeyCredentialId.encode(credential.credentialId))

    @Test
    fun `missing and empty allow lists require discoverability`() {
        for (request in listOf("{}", """{"allowCredentials":[]}""")) {
            val allowed = parseWebAuthnAllowedCredentialDescriptors(request, Json)
            assertFalse(allowed.isAllowCredentialsSupplied)
            assertTrue(allowed.allows(credential))
            assertFalse(allowed.allows(credential.copy(discoverable = false)))
        }
    }

    @Test
    fun `allow list matches id and type and may select non discoverable credentials`() {
        val allowed = parseWebAuthnAllowedCredentialDescriptors(
            """{"allowCredentials":[{"type":"unknown","id":"YQ"},{"type":"public-key","id":"$encodedId"}]}""",
            Json,
        )
        assertTrue(allowed.isAllowCredentialsSupplied)
        assertEquals(1, allowed.descriptors.size)
        assertTrue(allowed.allows(credential.copy(discoverable = false)))
        assertFalse(allowed.allows(credential.copy(keyType = "password")))
        assertFalse(allowed.allows(credential.copy(credentialId = "YQ")))
    }

    @Test
    fun `unknown descriptor types never enable discoverable fallback`() {
        assertFailsWith<WebAuthnNotAllowedException> {
            parseWebAuthnAllowedCredentialDescriptors(
                """{"allowCredentials":[{"type":"unknown","id":"$encodedId"}]}""",
                Json,
            )
        }
    }

    @Test
    fun `all descriptor ids are decoded before matching or ignoring unknown types`() {
        for (type in listOf("public-key", "unknown")) {
            assertFailsWith<WebAuthnEncodingException> {
                parseWebAuthnAllowedCredentialDescriptors(
                    """{"allowCredentials":[{"type":"public-key","id":"$encodedId"},{"type":"$type","id":"%%%"}]}""",
                    Json,
                )
            }
            assertFailsWith<WebAuthnEncodingException> {
                findExcludedPasskeyCredentialOrNull(
                    options(listOf(CreatePasskeyPublicKeyCredentialDescriptor(type, "%%%"))),
                    credential.rpId,
                    listOf(credential),
                )
            }
        }
    }

    @Test
    fun `exclusion matches type id and exact relying party`() {
        val options = options(listOf(CreatePasskeyPublicKeyCredentialDescriptor("public-key", encodedId)))
        assertSame(credential, findExcludedPasskeyCredentialOrNull(options, credential.rpId, listOf(credential)))
        assertNull(findExcludedPasskeyCredentialOrNull(options, "other.example", listOf(credential)))
        assertNull(
            findExcludedPasskeyCredentialOrNull(options, credential.rpId, listOf(credential.copy(keyType = "unknown"))),
        )
        assertNull(
            findExcludedPasskeyCredentialOrNull(options, credential.rpId, listOf(credential.copy(credentialId = "YQ"))),
        )
    }

    @Test
    fun `credential ids round trip UUID and arbitrary byte identifiers`() {
        for (id in listOf(credential.credentialId, "YQ")) {
            assertEquals(id, PasskeyCredentialId.decode(PasskeyCredentialId.encode(id)))
        }
    }

    private fun options(descriptors: List<CreatePasskeyPublicKeyCredentialDescriptor>) = CreatePasskey(
        challenge = "YQ",
        excludeCredentials = descriptors,
        pubKeyCredParams = emptyList(),
        rp = CreatePasskeyRelyingParty(credential.rpId, "Example"),
        user = CreatePasskeyUser("Yg", "alice", "Alice"),
    )
}
