package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.BiometricsException
import com.artemchep.autotype.BiometricsStatus
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_HW_UNAVAILABLE
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_KEY_INVALIDATED
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_LOCKOUT
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_NEGATIVE_BUTTON
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_UNKNOWN
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_USER_CANCELED

/**
 * Converts a failure of the native desktop
 * biometrics into the common [BiometricAuthException].
 */
internal fun Throwable.toBiometricAuthException(): BiometricAuthException {
    val code = when ((this as? BiometricsException)?.status) {
        BiometricsStatus.USER_CANCELED -> ERROR_USER_CANCELED
        BiometricsStatus.CREDENTIAL_NOT_FOUND -> ERROR_KEY_INVALIDATED
        BiometricsStatus.SECURITY_DEVICE_LOCKED -> ERROR_LOCKOUT
        BiometricsStatus.UNAVAILABLE -> ERROR_HW_UNAVAILABLE
        BiometricsStatus.USER_PREFERS_PASSWORD -> ERROR_NEGATIVE_BUTTON
        BiometricsStatus.SUCCESS,
        BiometricsStatus.UNKNOWN,
        null,
            -> ERROR_UNKNOWN
    }
    return BiometricAuthException(code, message.orEmpty())
}
