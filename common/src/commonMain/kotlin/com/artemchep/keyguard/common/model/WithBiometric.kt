package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.LeBiometricCipher

class WithBiometric(
    val getCipher: suspend () -> LeBiometricCipher,
    val getCreateIo: () -> IO<Unit>,
)