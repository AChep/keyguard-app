package com.artemchep.keyguard.feature.biometric

import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_CANCELED
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricPromptEffectTest {
    @Test
    fun `background window rejection is treated as cancellation`() {
        val error = IllegalStateException("Keyguard is not the foreground application")

        assertEquals(ERROR_CANCELED, error.toBiometricAuthException().code)
    }

    @Test
    fun `unexpected native failure remains an error`() {
        val error = IllegalStateException("native failure")

        assertEquals(ERROR_UNKNOWN, error.toBiometricAuthException().code)
    }
}
