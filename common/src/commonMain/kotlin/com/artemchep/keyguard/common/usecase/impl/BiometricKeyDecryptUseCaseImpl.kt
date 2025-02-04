package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.encode
import org.kodein.di.DirectDI

class BiometricKeyDecryptUseCaseImpl() : BiometricKeyDecryptUseCase {
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        cipher: IO<LeBiometricCipher>,
        encryptedMasterKey: ByteArray,
    ): IO<MasterKey> = encryptedMasterKey
        .encode(cipher)
        .map(::MasterKey)
}