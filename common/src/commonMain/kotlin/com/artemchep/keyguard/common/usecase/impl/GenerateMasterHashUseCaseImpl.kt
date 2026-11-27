package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase

class GenerateMasterHashUseCaseImpl(
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
) : GenerateMasterHashUseCase {
    companion object {
        private const val TAG = "GenerateMasterHashUseCaseImpl"
    }

    private val utils = GenerateMasterHashUtils(
        cryptoGenerator = cryptoGenerator,
    )

    override fun invoke(
        password: MasterPassword,
        salt: MasterPasswordSalt,
        kdf: MasterKdf,
    ): IO<MasterPasswordHash> = utils
        .hash(
            password = password.byteArray,
            salt = salt.byteArray,
            kdf = kdf,
        )
        .map { bytes ->
            MasterPasswordHash(
                kdf = kdf,
                byteArray = bytes,
            )
        }
        .measure { ms, _ ->
            val version = FingerprintPassword.Derived.versionOf(kdf)
            val msg = "Generated a master hash v${version.raw} ($kdf) in $ms"
            logRepository.add(TAG, msg)
        }
}
