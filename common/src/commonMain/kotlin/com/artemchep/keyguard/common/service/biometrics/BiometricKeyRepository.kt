package com.artemchep.keyguard.common.service.biometrics

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io

/**
 * Owns the platform key used to protect the persisted biometric unlock
 * binding. Key removal remains available even when biometric authentication
 * itself is unavailable.
 */
interface BiometricKeyRepository {
    fun delete(): IO<Unit>

    /**
     * Whether the platform key behind the saved binding is usable right
     * now. Most platforms keep the key for as long as the binding exists,
     * Linux keeps it in memory only and loses it when the app exits.
     */
    fun exists(): IO<Boolean> = io(true)
}
