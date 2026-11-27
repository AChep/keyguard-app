package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.RewrapMasterKeyUseCase

class AuthGenerateMasterKeyUseCaseImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val rewrapMasterKeyUseCase: RewrapMasterKeyUseCase,
) : AuthGenerateMasterKeyUseCase {
    override fun invoke(
        kdf: MasterKdf.Argon2id,
    ) = { password: MasterPassword ->
        ioEffect {
            val key = MasterKey(
                version = MasterKdfVersion.V2,
                byteArray = cryptoGenerator.seed(MasterKeyWrapping.MASTER_KEY_SIZE),
            )
            // Wipe the new key if it never leaves this function.
            @Suppress("TooGenericExceptionCaught")
            val token = try {
                rewrapMasterKeyUseCase(key, password, kdf).bind()
            } catch (e: Throwable) {
                key.byteArray.fill(0)
                throw e
            }
            AuthResult(
                key = key,
                token = token,
            )
        }
    }
}
