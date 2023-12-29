package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.core.session.util.encode
import com.artemchep.keyguard.platform.LeCipher
import org.kodein.di.DirectDI

class BiometricKeyDecryptUseCaseImpl() : BiometricKeyDecryptUseCase {
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        cipher: IO<LeCipher>,
        encryptedMasterKey: ByteArray,
    ): IO<MasterKey> = encryptedMasterKey
        .encode(cipher)
        .map(::MasterKey)
}