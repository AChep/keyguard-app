package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AuthConfirmMasterKeyUseCaseImpl(
    private val generateMasterHashUseCase: GenerateMasterHashUseCase,
    private val generateMasterKeyUseCase: GenerateMasterKeyUseCase,
) : AuthConfirmMasterKeyUseCase {
    constructor(directDI: DirectDI) : this(
        generateMasterHashUseCase = directDI.instance(),
        generateMasterKeyUseCase = directDI.instance(),
    )

    override fun invoke(
        salt: MasterPasswordSalt,
        hash: MasterPasswordHash,
    ) = { password: MasterPassword ->
        // Generate a hash from the given password and known
        // salt, to identify if the password is valid.
        generateMasterHashUseCase(password, salt)
            .flatMap { h ->
                if (!h.byteArray.contentEquals(hash.byteArray)) {
                    val e = PasswordMismatchException()
                    return@flatMap ioRaise(e)
                }

                generateMasterKeyUseCase(password, hash)
            }
            .map { key ->
                AuthResult(
                    key = key,
                    token = FingerprintPassword(
                        hash = hash,
                        salt = salt,
                    ),
                )
            }
    }
}