package com.artemchep.keyguard.common.usecase.impl

import arrow.core.compose
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.fold
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ConfirmAccessByPasswordUseCaseImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val authConfirmMasterKeyUseCase: AuthConfirmMasterKeyUseCase,
) : ConfirmAccessByPasswordUseCase {
    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
        authConfirmMasterKeyUseCase = directDI.instance(),
    )

    override fun invoke(
        password: String,
    ): IO<Boolean> = keyReadWriteRepository
        .get().toIO()
        .effectMap { fingerprint ->
            requireNotNull(fingerprint) {
                "Identity fingerprint must not be null!"
            }
            val salt = fingerprint.master.salt
            val hash = fingerprint.master.hash
            val version = fingerprint.version
            authConfirmMasterKeyUseCase(salt, hash, version)
                .compose { password: String ->
                    MasterPassword.of(password)
                }
                .invoke(password)
                .fold(
                    ifLeft = { false },
                    ifRight = { true },
                )
        }
}
