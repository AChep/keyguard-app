package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTarget
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
                allowedCredentials = null,
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
                allowedCredentials = null,
                rpId = "example.com",
            ),
        ).bind()

        assertFalse(result)
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
                allowedCredentials = null,
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
                allowedCredentials = null,
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
                credentialId = "credential-id",
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                allowedCredentials = listOf(
                    PasskeyTarget.AllowedCredential(
                        credentialId = "credential-id",
                        type = "public-key",
                    ),
                ),
                rpId = "example.com",
            ),
        ).bind()

        assertTrue(result)
    }

    @Test
    fun `allowed credential still requires exact rp id`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                credentialId = "credential-id",
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                allowedCredentials = listOf(
                    PasskeyTarget.AllowedCredential(
                        credentialId = "credential-id",
                    ),
                ),
                rpId = "login.example.com",
            ),
        ).bind()

        assertFalse(result)
    }

    @Test
    fun `allowed credential still requires matching descriptor type`() = runTest {
        val result = passkeyTargetCheck(
            credential = credential(
                credentialId = "credential-id",
                rpId = "example.com",
                discoverable = false,
            ),
            target = PasskeyTarget(
                allowedCredentials = listOf(
                    PasskeyTarget.AllowedCredential(
                        credentialId = "credential-id",
                        type = "unknown",
                    ),
                ),
                rpId = "example.com",
            ),
        ).bind()

        assertFalse(result)
    }

    private fun credential(
        credentialId: String = "credential-id",
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
        userHandle = "user-handle",
        userName = "user-name",
        userDisplayName = "User Name",
        discoverable = discoverable,
        creationDate = Instant.fromEpochMilliseconds(0),
    )
}
