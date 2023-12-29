package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AuthGenerateMasterKeyUseCaseImpl(
    private val generateMasterHashUseCase: GenerateMasterHashUseCase,
    private val generateMasterKeyUseCase: GenerateMasterKeyUseCase,
    private val generateMasterSaltUseCase: GenerateMasterSaltUseCase,
) : AuthGenerateMasterKeyUseCase {
    constructor(directDI: DirectDI) : this(
        generateMasterHashUseCase = directDI.instance(),
        generateMasterKeyUseCase = directDI.instance(),
        generateMasterSaltUseCase = directDI.instance(),
    )

    override fun invoke() = { password: MasterPassword ->
        // Generate a hash from the given password and known
        // salt, to identify if the password is valid.
        generateMasterSaltUseCase()
            .effectMap { salt ->
                val hash = generateMasterHashUseCase(password, salt).bind()
                val key = generateMasterKeyUseCase(password, hash).bind()
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