package com.artemchep.keyguard.common.service.hibp.passwords.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.PasswordPwnage
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceRemote
import com.artemchep.keyguard.common.util.toHex
import kotlinx.coroutines.Dispatchers

/**
 * @author Artem Chepurnyi
 */
class PasswordPwnageDataSourceRemoteImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val hibpRepository: HibpRepository,
) : PasswordPwnageDataSourceRemote {
    override fun check(
        password: String,
    ): IO<PasswordPwnage> = ioEffect(Dispatchers.Default) {
        val hash = cryptoGenerator.hashSha1(password.encodeToByteArray())
            .toHex()
            .uppercase()
        hash
    }
        .flatMap(hibpRepository::getPwnedPasswordOccurrences)
        .map { occurrences ->
            PasswordPwnage(
                occurrences = occurrences,
            )
        }
}
