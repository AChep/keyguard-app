package com.artemchep.keyguard.common.service.biometrics

import com.artemchep.keyguard.common.io.IO

/**
 * Owns the platform key used to protect the persisted biometric unlock
 * binding. Key removal remains available even when biometric authentication
 * itself is unavailable.
 */
interface BiometricKeyRepository {
    fun delete(): IO<Unit>
}
