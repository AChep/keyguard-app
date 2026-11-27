package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.MasterKdfOutOfMemoryException
import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmAccessByPasswordUseCaseImplTest {
    private val kdf = MasterKdf.Argon2id(
        iterations = 4,
        memoryKib = 32768,
        parallelism = 2,
    )

    private val token = FingerprintPassword(
        hash = MasterPasswordHash(kdf, byteArrayOf(1)),
        salt = MasterPasswordSalt(byteArrayOf(2)),
    )

    @Test
    fun `matching password confirms access with the key derivation of the vault`() {
        var usedKdf: MasterKdf? = null
        val useCase = useCase { kdf ->
            usedKdf = kdf
            io(
                AuthResult(
                    kdf = kdf,
                    key = MasterKey(kdf.version, byteArrayOf(3)),
                    token = token,
                ),
            )
        }

        assertTrue(useCase("password").bindBlocking())
        assertEquals(kdf, usedKdf)
    }

    @Test
    fun `wrong password denies access`() {
        val useCase = useCase {
            ioRaise(PasswordMismatchException())
        }

        assertFalse(useCase("password").bindBlocking())
    }

    @Test
    fun `other failures are not reported as a wrong password`() {
        val useCase = useCase {
            ioRaise(MasterKdfOutOfMemoryException(null))
        }

        assertFailsWith<MasterKdfOutOfMemoryException> {
            useCase("password").bindBlocking()
        }
    }

    private fun useCase(
        derive: (MasterKdf) -> IO<AuthResult>,
    ) = ConfirmAccessByPasswordUseCaseImpl(
        keyReadWriteRepository = object : FingerprintReadWriteRepository {
            override fun get(): Flow<Fingerprint?> = flowOf(
                Fingerprint(
                    kdf = kdf,
                    master = token,
                    biometric = null,
                ),
            )

            override fun put(key: Fingerprint?): IO<Unit> = ioUnit()

            override fun update(transform: (Fingerprint?) -> Fingerprint?): IO<Unit> = ioUnit()
        },
        authConfirmMasterKeyUseCase = object : AuthConfirmMasterKeyUseCase {
            override fun invoke(
                salt: MasterPasswordSalt,
                hash: MasterPasswordHash,
                kdf: MasterKdf,
            ): AuthMasterKeyUseCase = { derive(kdf) }
        },
    )
}
