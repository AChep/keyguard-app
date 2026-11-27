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
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
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

    private val wrapped = FingerprintPassword.Wrapped(
        kdf = kdf,
        salt = MasterPasswordSalt(byteArrayOf(2)),
        wrappedKey = byteArrayOf(1),
    )

    private val derived = FingerprintPassword.Derived(
        kdf = MasterKdf.Argon2id.V1,
        hash = MasterPasswordHash(MasterKdf.Argon2id.V1, byteArrayOf(4, 5)),
        salt = MasterPasswordSalt(byteArrayOf(6)),
    )

    @Test
    fun `matching password confirms access to a wrapped vault`() {
        var usedToken: FingerprintPassword? = null
        val key = byteArrayOf(3)
        val useCase = useCase(wrapped) { token ->
            usedToken = token
            io(
                AuthResult(
                    key = MasterKey(MasterKdfVersion.V2, key),
                    token = token,
                ),
            )
        }

        assertTrue(useCase("password").bindBlocking())
        assertEquals<FingerprintPassword?>(wrapped, usedToken)
        // Only the answer is needed: the key is wiped.
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun `a derived vault checks the hash only`() {
        val useCase = useCase(
            token = derived,
            hash = { io(derived.hash.copy(byteArray = derived.hash.byteArray.copyOf())) },
        ) { error("The master key must not be derived") }

        assertTrue(useCase("password").bindBlocking())
    }

    @Test
    fun `a derived vault denies access on another hash`() {
        val useCase = useCase(
            token = derived,
            hash = { io(MasterPasswordHash(MasterKdf.Argon2id.V1, byteArrayOf(4, 6))) },
        ) { error("The master key must not be derived") }

        assertFalse(useCase("password").bindBlocking())
    }

    @Test
    fun `wrong password denies access`() {
        val useCase = useCase(wrapped) {
            ioRaise(PasswordMismatchException())
        }

        assertFalse(useCase("password").bindBlocking())
    }

    @Test
    fun `other failures are not reported as a wrong password`() {
        val useCase = useCase(wrapped) {
            ioRaise(MasterKdfOutOfMemoryException(null))
        }

        assertFailsWith<MasterKdfOutOfMemoryException> {
            useCase("password").bindBlocking()
        }
    }

    private fun useCase(
        token: FingerprintPassword,
        hash: () -> IO<MasterPasswordHash> = { error("The hash must not be derived") },
        derive: (FingerprintPassword) -> IO<AuthResult>,
    ) = ConfirmAccessByPasswordUseCaseImpl(
        keyReadWriteRepository = object : FingerprintReadWriteRepository {
            override fun get(): Flow<Fingerprint?> = flowOf(
                Fingerprint(
                    master = token,
                    biometric = null,
                ),
            )

            override fun put(key: Fingerprint?): IO<Unit> = ioUnit()

            override fun update(transform: (Fingerprint?) -> Fingerprint?): IO<Unit> = ioUnit()
        },
        generateMasterHashUseCase = object : GenerateMasterHashUseCase {
            override fun invoke(
                password: MasterPassword,
                salt: MasterPasswordSalt,
                kdf: MasterKdf,
            ): IO<MasterPasswordHash> = hash()
        },
        authConfirmMasterKeyUseCase = object : AuthConfirmMasterKeyUseCase {
            override fun invoke(
                token: FingerprintPassword,
            ): AuthMasterKeyUseCase = { derive(token) }
        },
    )
}
