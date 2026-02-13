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
import org.kodein.di.DirectDI
import org.kodein.di.instance

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

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    override fun invoke(
        password: MasterPassword,
        salt: MasterPasswordHash,
    ): IO<MasterKey> = utils
        .hash(
            password = password.byteArray,
            salt = salt.byteArray,
            version = salt.version,
        )
        .map { bytes ->
            MasterKey(
                version = salt.version,
                byteArray = bytes,
            )
        }
        .measure { ms, _ ->
            val version = salt.version
            val msg = "Generated a master key v${version.raw} in $ms"
            logRepository.add(TAG, msg)
        }
}
