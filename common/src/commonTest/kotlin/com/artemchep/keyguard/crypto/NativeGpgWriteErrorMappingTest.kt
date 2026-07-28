package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NativeGpgWriteErrorMappingTest {
    @Test
    fun mapsUnsupportedKeyVersionToLegacyDomainException() {
        val failure = assertFailsWith<GpgUnsupportedKeyVersionException> {
            translateNativeOpenPgpWriteError {
                throw NativeCryptoException(
                    operation = "open_pgp_decrypt",
                    code = NativeCryptoErrorCode.UNSUPPORTED_KEY_VERSION,
                )
            }
        }

        assertEquals(3, failure.version)
    }

    @Test
    fun preservesOtherNativeFailures() {
        val expected = NativeCryptoException(
            operation = "open_pgp_decrypt",
            code = NativeCryptoErrorCode.AUTHENTICATION_FAILED,
        )
        val actual = assertFailsWith<NativeCryptoException> {
            translateNativeOpenPgpWriteError {
                throw expected
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun mapsEncryptionNoUsableKeyToLegacyFailure() {
        val expected = NativeCryptoException(
            operation = "open_pgp_encrypt",
            code = NativeCryptoErrorCode.NO_USABLE_KEY,
        )
        val actual = assertFailsWith<IllegalStateException> {
            translateNativeOpenPgpWriteError(
                noUsableKeyMeansLegacyFailure = true,
            ) {
                throw expected
            }
        }

        assertSame(expected, actual.cause)
    }

    @Test
    fun preservesEncryptionInvalidArgumentInNoUsableKeyContext() {
        val expected = NativeCryptoException(
            operation = "open_pgp_encrypt",
            code = NativeCryptoErrorCode.INVALID_ARGUMENT,
        )
        val actual = assertFailsWith<NativeCryptoException> {
            translateNativeOpenPgpWriteError(
                noUsableKeyMeansLegacyFailure = true,
            ) {
                throw expected
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun preservesInvalidArgumentOutsideNoUsableKeyContext() {
        val expected = NativeCryptoException(
            operation = "open_pgp_sign",
            code = NativeCryptoErrorCode.INVALID_ARGUMENT,
        )
        val actual = assertFailsWith<NativeCryptoException> {
            translateNativeOpenPgpWriteError {
                throw expected
            }
        }

        assertSame(expected, actual)
    }
}
