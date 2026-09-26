package com.artemchep.keyguard.util.webauthn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnAuthenticatorDataFactoryTest {
    @Test
    fun `authenticator data rejects negative sign count`() {
        val factory = WebAuthnAuthenticatorDataFactory(
            aaguid = ByteArray(16),
            hashSha256 = { ByteArray(32) },
        )

        val error = assertFailsWith<IllegalArgumentException> {
            factory.encodeAuthenticatorData(
                rpId = "example.com",
                signCount = -1,
                credentialId = byteArrayOf(0x01),
                credentialPublicKey = null,
                userVerified = true,
                userPresent = true,
            )
        }

        assertEquals(
            "WebAuthn signCount must be non-negative.",
            error.message,
        )
    }
}
