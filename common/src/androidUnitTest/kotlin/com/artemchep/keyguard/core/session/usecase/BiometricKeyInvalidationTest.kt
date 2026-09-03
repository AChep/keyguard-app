package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.model.BiometricBindingException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BiometricKeyInvalidationTest {
    @Test
    fun `unrecoverable key is mapped to an invalid biometric binding`() {
        val cause = UnrecoverableKeyException("Key is unavailable.")

        val exception = assertFailsWith<BiometricBindingException> {
            withBiometricBindingFailureMapping<Unit> {
                throw cause
            }
        }

        assertSame(cause, exception.cause)
    }

    @Test
    fun `unrelated keystore failure is preserved`() {
        val cause = KeyStoreException("Keystore is temporarily unavailable.")

        val exception = assertFailsWith<KeyStoreException> {
            withBiometricBindingFailureMapping<Unit> {
                throw cause
            }
        }

        assertSame(cause, exception)
    }

    @Test
    fun `successful biometric key operation is preserved`() {
        assertEquals(42, withBiometricBindingFailureMapping { 42 })
    }
}
