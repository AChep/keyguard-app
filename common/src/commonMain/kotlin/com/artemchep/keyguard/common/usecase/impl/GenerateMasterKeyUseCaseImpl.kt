package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase

class GenerateMasterKeyUseCaseImpl(
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
) : GenerateMasterKeyUseCase {
    companion object {
        private const val TAG = "GenerateMasterKeyUseCaseImpl"
    }

    private val utils = GenerateMasterKeyUtils(
        cryptoGenerator = cryptoGenerator,
    )

    override fun invoke(
        password: MasterPassword,
        salt: MasterPasswordHash,
    ): IO<MasterKey> = utils
        .hash(
            password = password.byteArray,
            salt = salt.byteArray,
            kdf = salt.kdf,
        )
        .map { bytes ->
            MasterKey(
                version = salt.kdf.version,
                byteArray = bytes,
            )
        }
        .measure { ms, _ ->
            val kdf = salt.kdf
            val msg = "Generated a master key v${kdf.version.raw} ($kdf) in $ms"
            logRepository.add(TAG, msg)
        }
}
