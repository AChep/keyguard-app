package com.artemchep.keyguard.android

import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SyntaxError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticatorDataFactory
import com.artemchep.keyguard.util.webauthn.WebAuthnCallerContext
import com.artemchep.keyguard.util.webauthn.WebAuthnCredential
import com.artemchep.keyguard.util.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotAllowedException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotSupportedException
import com.artemchep.keyguard.util.webauthn.WebAuthnSyntaxException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class PasskeyProviderGetRequestTest {
    @Test
    fun `rp mismatch maps to NotAllowed without accessing the stored key`() {
        val authenticator = WebAuthnAuthenticator(
            json = Json,
            authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(
                aaguid = ByteArray(16),
                hashSha256 = { error("RP mismatch must be rejected before hashing") },
                hashMd5 = { error("RP mismatch must be rejected before hashing") },
            ),
            decodeStoredPrivateKey = { error("RP mismatch must be rejected before decoding the key") },
            hashSha256 = { error("RP mismatch must be rejected before hashing") },
        )
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            mapGetWebAuthnExceptions {
                authenticator.getAssertion(
                    request = WebAuthnAssertionRequest(
                        challenge = byteArrayOf(1),
                        userVerification = "required",
                        allowedCredentials = WebAuthnAllowedCredentialDescriptors(false, emptyList()),
                    ),
                    context = WebAuthnCallerContext("https://example.com", "example.com"),
                    credential = WebAuthnCredential(
                        credentialId = "123e4567-e89b-12d3-a456-426614174000",
                        keyType = "public-key",
                        keyAlgorithm = "ECDSA",
                        keyCurve = "P-256",
                        keyValue = "AQID",
                        rpId = "other.example",
                        discoverable = true,
                    ),
                    userVerified = true,
                )
            }
        }
        assertIs<NotAllowedError>(error.domError)
        val cause = assertIs<WebAuthnNotAllowedException>(error.cause)
        assertEquals(cause.message, error.message)
    }

    @Test
    fun `maps NotAllowed errors to credential manager`() {
        val cause = WebAuthnNotAllowedException("test message")
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            mapGetWebAuthnExceptions { throw cause }
        }
        assertIs<NotAllowedError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `maps Encoding errors to credential manager`() {
        val cause = WebAuthnEncodingException("test message")
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            mapGetWebAuthnExceptions { throw cause }
        }
        assertIs<EncodingError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `maps NotSupported errors to credential manager`() {
        val cause = WebAuthnNotSupportedException("test message")
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            mapGetWebAuthnExceptions { throw cause }
        }
        assertIs<NotSupportedError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `maps Syntax errors to credential manager`() {
        val cause = WebAuthnSyntaxException("test message")
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            mapGetWebAuthnExceptions { throw cause }
        }
        assertIs<SyntaxError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }
}
