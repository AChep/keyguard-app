package com.artemchep.keyguard.util.webauthn

import kotlin.test.Test
import kotlin.test.assertEquals

class WebAuthnUserVerificationTest {
    // Spec coverage: Section 5.8.6 defines required/preferred/discouraged user
    // verification, and Section 6.1 defines the authenticator-data UV flag as
    // the bit reporting whether user verification was performed.
    @Test
    fun `user verified flag follows actual verification result for required mode`() {
        assertEquals(
            false,
            webAuthnUserVerifiedFlag(
                requirement = "required",
                userVerified = false,
            ),
        )
        assertEquals(
            true,
            webAuthnUserVerifiedFlag(
                requirement = "required",
                userVerified = true,
            ),
        )
    }

    @Test
    fun `user verified flag follows actual verification result for preferred mode`() {
        assertEquals(
            false,
            webAuthnUserVerifiedFlag(
                requirement = "preferred",
                userVerified = false,
            ),
        )
        assertEquals(
            true,
            webAuthnUserVerifiedFlag(
                requirement = "preferred",
                userVerified = true,
            ),
        )
    }

    @Test
    fun `user verified flag defaults omitted mode to preferred`() {
        assertEquals(
            false,
            webAuthnUserVerifiedFlag(
                requirement = null,
                userVerified = false,
            ),
        )
        assertEquals(
            true,
            webAuthnUserVerifiedFlag(
                requirement = null,
                userVerified = true,
            ),
        )
    }

    @Test
    fun `user verified flag is suppressed for discouraged mode`() {
        assertEquals(
            false,
            webAuthnUserVerifiedFlag(
                requirement = "discouraged",
                userVerified = true,
            ),
        )
    }
}
