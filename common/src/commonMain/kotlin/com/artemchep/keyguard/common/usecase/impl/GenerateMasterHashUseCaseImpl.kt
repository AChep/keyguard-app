package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GenerateMasterHashUseCaseImpl(
    private val cryptoGenerator: CryptoGenerator,
) : GenerateMasterHashUseCase {
    companion object {
        // Should be minimum 10k, as per
        // https://pages.nist.gov/800-63-3/sp800-63b.html#sec5
        private const val HASH_ITERATIONS = 100000
    }

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
    )

    override fun invoke(
        password: MasterPassword,
        salt: MasterPasswordSalt,
    ): IO<MasterPasswordHash> = ioEffect(Dispatchers.Default) {
        encode(
            password = password.byteArray,
            salt = salt.byteArray,
        )
    }.map(::MasterPasswordHash)

    private fun encode(
        password: ByteArray,
        salt: ByteArray,
    ) = cryptoGenerator.pbkdf2(
        seed = password,
        salt = salt,
        iterations = HASH_ITERATIONS,
    )
}