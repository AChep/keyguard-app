package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import org.kodein.di.DirectDI
import org.kodein.di.instance

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

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    override fun invoke(
        password: MasterPassword,
        salt: MasterPasswordSalt,
        version: MasterKdfVersion,
    ): IO<MasterPasswordHash> = utils
        .hash(
            password = password.byteArray,
            salt = salt.byteArray,
            version = version,
        )
        .map { bytes ->
            MasterPasswordHash(
                version = version,
                byteArray = bytes,
            )
        }
        .measure { ms, _ ->
            val msg = "Generated a master hash v${version.raw} in $ms"
            logRepository.add(TAG, msg)
        }
}
