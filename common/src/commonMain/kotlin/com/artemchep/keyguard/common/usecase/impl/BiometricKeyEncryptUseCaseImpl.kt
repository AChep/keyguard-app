package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.core.session.util.encode
import com.artemchep.keyguard.platform.LeCipher
import org.kodein.di.DirectDI

class BiometricKeyEncryptUseCaseImpl() : BiometricKeyEncryptUseCase {
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        cipher: IO<LeCipher>,
        masterKey: MasterKey,
    ): IO<ByteArray> = masterKey.byteArray
        .encode(cipher)
}