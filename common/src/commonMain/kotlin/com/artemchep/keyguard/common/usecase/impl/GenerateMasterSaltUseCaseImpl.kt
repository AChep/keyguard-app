package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import kotlinx.coroutines.Dispatchers

class GenerateMasterSaltUseCaseImpl(
    private val cryptoGenerator: CryptoGenerator,
) : GenerateMasterSaltUseCase {
    companion object {
        private const val SALT_SIZE_BYTES = 64
    }

    override fun invoke(): IO<MasterPasswordSalt> = ioEffect(Dispatchers.Default) {
        cryptoGenerator
            .seed(length = SALT_SIZE_BYTES)
    }.map(::MasterPasswordSalt)
}