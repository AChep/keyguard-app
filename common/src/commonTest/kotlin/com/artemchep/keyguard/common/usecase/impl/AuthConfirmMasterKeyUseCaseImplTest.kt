package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.fail

class AuthConfirmMasterKeyUseCaseImplTest {
    private val unsupported = MasterKdf.Unsupported(MasterKdfVersion.fromRaw(999))

    @Test
    fun `unsupported hash version maps to password mismatch`() {
        val generateMasterHashUseCase = object : GenerateMasterHashUseCase {
            override fun invoke(
                password: MasterPassword,
                salt: MasterPasswordSalt,
                kdf: MasterKdf,
            ): IO<MasterPasswordHash> = ioRaise(
                UnsupportedMasterKdfVersionException(
                    version = kdf.version,
                    type = "master-hash",
                ),
            )
        }
        val generateMasterKeyUseCase = object : GenerateMasterKeyUseCase {
            override fun invoke(
                password: MasterPassword,
                salt: MasterPasswordHash,
            ): IO<MasterKey> = ioRaise(
                AssertionError("master key generation must not run"),
            )
        }
        val useCase = AuthConfirmMasterKeyUseCaseImpl(
            generateMasterHashUseCase = generateMasterHashUseCase,
            generateMasterKeyUseCase = generateMasterKeyUseCase,
        )

        val result = useCase(
            salt = MasterPasswordSalt(byteArrayOf(1, 2, 3)),
            hash = MasterPasswordHash(
                kdf = unsupported,
                byteArray = byteArrayOf(9, 8, 7),
            ),
            kdf = unsupported,
        )(MasterPassword.of("password"))
            .attempt()
            .bindBlocking()

        when (result) {
            is Either.Left -> assertIs<PasswordMismatchException>(result.value)
            is Either.Right -> fail("expected a failure")
        }
    }

    @Test
    fun `unsupported key version maps to password mismatch`() {
        val persistedHash = MasterPasswordHash(
            kdf = unsupported,
            byteArray = byteArrayOf(9, 8, 7),
        )
        val generateMasterHashUseCase = object : GenerateMasterHashUseCase {
            override fun invoke(
                password: MasterPassword,
                salt: MasterPasswordSalt,
                kdf: MasterKdf,
            ): IO<MasterPasswordHash> = io(persistedHash)
        }
        val generateMasterKeyUseCase = object : GenerateMasterKeyUseCase {
            override fun invoke(
                password: MasterPassword,
                salt: MasterPasswordHash,
            ): IO<MasterKey> = ioRaise(
                UnsupportedMasterKdfVersionException(
                    version = salt.kdf.version,
                    type = "master-key",
                ),
            )
        }
        val useCase = AuthConfirmMasterKeyUseCaseImpl(
            generateMasterHashUseCase = generateMasterHashUseCase,
            generateMasterKeyUseCase = generateMasterKeyUseCase,
        )

        val result = useCase(
            salt = MasterPasswordSalt(byteArrayOf(1, 2, 3)),
            hash = persistedHash,
            kdf = unsupported,
        )(MasterPassword.of("password"))
            .attempt()
            .bindBlocking()

        when (result) {
            is Either.Left -> assertIs<PasswordMismatchException>(result.value)
            is Either.Right -> fail("expected a failure")
        }
    }
}
