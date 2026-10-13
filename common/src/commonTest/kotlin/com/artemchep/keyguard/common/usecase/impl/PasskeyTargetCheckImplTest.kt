package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnPublicKeyCredentialDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * WebAuthn Level 3 candidate-filter coverage for vault-side passkey
 * suggestions: discoverable credentials when no allowCredentials list exists,
 * exact RP ID scoping, and descriptor id/type matching.
 */
class PasskeyTargetCheckImplTest {
    private val passkeyTargetCheck = PasskeyTargetCheckImpl()

    // Spec coverage: Section 5.1.4.2 says an empty credential filter asks for
    // any credential scoped to the RP ID; this local picker can only surface
    // stored discoverable credentials in that path.
    @Test
    fun `matching rp id allows discoverable credential`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "example.com",
                discoverable = true,
            ),
            target = PasskeyTarget(
                rpId = "example.com",
            ),
        ).bind()

        assertTrue(result)
    }

    @Test
    fun `missing allowed credentials rejects non discoverable credential`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                rpId = "example.com",
            ),
        ).bind()

        assertFalse(result)
    }

    // Without an allow list, the relying party identifies the account by the
    // user handle, so a credential without a readable one would fail after the
    // user verified. With an allow list, it stays usable.
    @Test
    fun `missing allowed credentials rejects credential without a readable user handle`() = runTest {
        for (userHandle in listOf(null, "%%%")) {
            val credential = credential(rpId = "example.com", discoverable = true)
                .copy(userHandle = userHandle)
            val discoverable = PasskeyTarget(rpId = "example.com")
            assertFalse(passkeyTargetCheck(credential, discoverable).bind())
            val allowed = discoverable.copy(allowed = allowing(CREDENTIAL_ID))
            assertTrue(passkeyTargetCheck(credential, allowed).bind())
        }
    }

    // Spec coverage: Section 5.1.4.1 resolves the request RP ID; Section 6.1
    // defines rpIdHash inside authenticator data, which Section 6.3.3 signs
    // during authenticatorGetAssertion. A valid parent-domain RP ID cannot be
    // inferred after defaulting to a subdomain.
    @Test
    fun `parent rp id does not match defaulted subdomain request`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "example.com",
                discoverable = true,
            ),
            target = PasskeyTarget(
                rpId = "login.example.com",
            ),
        ).bind()

        assertFalse(result)
    }

    @Test
    fun `subdomain rp id does not match parent rp id request`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "login.example.com",
                discoverable = true,
            ),
            target = PasskeyTarget(
                rpId = "example.com",
            ),
        ).bind()

        assertFalse(result)
    }

    // Spec coverage: Section 5.1.4.2 filters allowCredentials by rpId, id, and
    // type; matching descriptors can surface non-discoverable credentials.
    @Test
    fun `allowed credential permits non discoverable credential with exact rp id`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                rpId = "example.com",
                allowed = allowing(CREDENTIAL_ID),
            ),
        ).bind()

        assertTrue(result)
    }

    // Storage may spell the same wire ID differently than the request does.
    @Test
    fun `allowed credential matches any stored spelling of its id`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                credentialId = CREDENTIAL_ID.uppercase(),
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                rpId = "example.com",
                allowed = allowing(CREDENTIAL_ID),
            ),
        ).bind()

        assertTrue(result)
    }

    @Test
    fun `allowed credential still requires exact rp id`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                rpId = "login.example.com",
                allowed = allowing(CREDENTIAL_ID),
            ),
        ).bind()

        assertFalse(result)
    }

    @Test
    fun `allowed credential still requires matching descriptor type`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                rpId = "example.com",
                allowed = allowing(CREDENTIAL_ID, type = "unknown"),
            ),
        ).bind()

        assertFalse(result)
    }

    // The picker never offers a passkey the authenticator cannot sign with.
    @Test
    fun `credential without a signable key or a readable id is never offered`() = runTest {
        val target = PasskeyTarget(rpId = "example.com")
        val unusable = listOf(
            credential(rpId = "example.com", discoverable = true).copy(keyValue = ""),
            credential(rpId = "example.com", discoverable = true).copy(keyCurve = "P-384"),
            credential(credentialId = "%%%", rpId = "example.com", discoverable = true),
        )
        for (credential in unusable) {
            assertFalse(passkeyTargetCheck(credential, target).bind(), "$credential")
        }
    }

    private fun allowing(
        credentialId: String,
        type: String = "public-key",
    ) = WebAuthnAllowedCredentialDescriptors(
        isAllowCredentialsSupplied = true,
        descriptors = listOf(WebAuthnPublicKeyCredentialDescriptor(type, credentialId)),
    )

    private fun credential(
        credentialId: String = CREDENTIAL_ID,
        rpId: String,
        discoverable: Boolean,
    ) = DSecret.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "key-value",
        rpId = rpId,
        rpName = null,
        counter = 0,
        userHandle = "dXNlcg",
        userName = "user-name",
        userDisplayName = "User Name",
        discoverable = discoverable,
        creationDate = Instant.fromEpochMilliseconds(0),
    )

    private companion object {
        const val CREDENTIAL_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
