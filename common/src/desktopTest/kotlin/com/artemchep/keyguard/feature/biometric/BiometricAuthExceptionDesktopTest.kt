package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.BiometricsException
import com.artemchep.autotype.BiometricsStatus
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_LOCKOUT
import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricAuthExceptionDesktopTest {
    @Test
    fun `security device locked maps to biometric lockout`() {
        val source = BiometricsException(
            status = BiometricsStatus.SECURITY_DEVICE_LOCKED,
            message = "Touch ID is locked.",
        )

        val result = source.toBiometricAuthException()

        assertEquals(ERROR_LOCKOUT, result.code)
        assertEquals(source.message, result.message)
    }
}
