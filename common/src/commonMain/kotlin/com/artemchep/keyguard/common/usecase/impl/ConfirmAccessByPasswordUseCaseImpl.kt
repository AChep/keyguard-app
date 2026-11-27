package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.util.useAndClear

class ConfirmAccessByPasswordUseCaseImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val generateMasterHashUseCase: GenerateMasterHashUseCase,
    private val authConfirmMasterKeyUseCase: AuthConfirmMasterKeyUseCase,
) : ConfirmAccessByPasswordUseCase {
    override fun invoke(
        password: String,
    ): IO<Boolean> = keyReadWriteRepository
        .get().toIO()
        .effectMap { fingerprint ->
            requireNotNull(fingerprint) {
                "Identity fingerprint must not be null!"
            }
            val masterPassword = MasterPassword.of(password)
            masterPassword.byteArray.useAndClear {
                verify(fingerprint.master, masterPassword)
                    .attempt()
                    .bind()
                    .fold(
                        // Only a wrong password is a negative answer: other
                        // failures, such as running out of memory, are errors.
                        ifLeft = { e ->
                            if (e !is PasswordMismatchException) throw e
                            false
                        },
                        ifRight = { true },
                    )
            }
        }

    private fun verify(
        token: FingerprintPassword,
        password: MasterPassword,
    ): IO<Unit> = when (token) {
        // Only the hash stage runs: the
        // master key is not derived.
        is FingerprintPassword.Derived -> generateMasterHashUseCase
            .verifyPassword(token, password)

        is FingerprintPassword.Wrapped -> authConfirmMasterKeyUseCase(token)(password)
            .map { result ->
                // Only the answer is needed: wipe the key.
                result.key.byteArray.fill(0)
            }
    }
}
