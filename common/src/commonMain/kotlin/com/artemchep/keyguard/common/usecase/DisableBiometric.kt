package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/**
 * Removes biometric unlock data from both Keyguard's persisted state and the
 * platform's secure key storage.
 */
interface DisableBiometric : () -> IO<Unit>
