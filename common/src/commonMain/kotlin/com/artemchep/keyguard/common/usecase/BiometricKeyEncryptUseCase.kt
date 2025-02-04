package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.platform.LeBiometricCipher

interface BiometricKeyEncryptUseCase : (
    IO<LeBiometricCipher>,
    MasterKey,
) -> IO<ByteArray>
