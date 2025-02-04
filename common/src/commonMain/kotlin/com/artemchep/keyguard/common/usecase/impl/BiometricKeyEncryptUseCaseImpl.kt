package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.encode
import org.kodein.di.DirectDI

class BiometricKeyEncryptUseCaseImpl() : BiometricKeyEncryptUseCase {
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        cipher: IO<LeBiometricCipher>,
        masterKey: MasterKey,
    ): IO<ByteArray> = masterKey.byteArray
        .encode(cipher)
}