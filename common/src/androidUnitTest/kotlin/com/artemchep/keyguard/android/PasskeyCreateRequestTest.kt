package com.artemchep.keyguard.android

import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import com.artemchep.keyguard.util.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.util.webauthn.WebAuthnInvalidStateException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotSupportedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class PasskeyCreateRequestTest {
    @Test
    fun `maps NotSupported errors to credential manager`() {
        val cause = WebAuthnNotSupportedException("test message")
        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            mapCreateWebAuthnExceptions { throw cause }
        }
        assertIs<NotSupportedError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `maps InvalidState errors to credential manager`() {
        val cause = WebAuthnInvalidStateException("test message")
        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            mapCreateWebAuthnExceptions { throw cause }
        }
        assertIs<InvalidStateError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `maps Encoding errors to credential manager`() {
        val cause = WebAuthnEncodingException("test message")
        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            mapCreateWebAuthnExceptions { throw cause }
        }
        assertIs<EncodingError>(error.domError)
        assertEquals(cause.message, error.message)
        assertSame(cause, error.cause)
    }
}
