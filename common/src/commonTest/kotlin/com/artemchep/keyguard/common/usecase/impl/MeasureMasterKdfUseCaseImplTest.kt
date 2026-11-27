package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class MeasureMasterKdfUseCaseImplTest {
    @Test
    fun `derives once with the given parameters and wipes the key`() {
        val kdf = MasterKdf.Argon2id(
            iterations = 2,
            memoryKib = 16384,
            parallelism = 1,
        )
        val usedKdfs = mutableListOf<MasterKdf>()
        val keyBytes = byteArrayOf(1, 2, 3)
        val useCase = MeasureMasterKdfUseCaseImpl(
            authGenerateMasterKeyUseCase = object : AuthGenerateMasterKeyUseCase {
                override fun invoke(kdf: MasterKdf): AuthMasterKeyUseCase = {
                    usedKdfs += kdf
                    io(
                        AuthResult(
                            kdf = kdf,
                            key = MasterKey(kdf.version, keyBytes),
                            token = FingerprintPassword(
                                hash = MasterPasswordHash(kdf, byteArrayOf(4)),
                                salt = MasterPasswordSalt(byteArrayOf(5)),
                            ),
                        ),
                    )
                }
            },
        )

        val duration = useCase(kdf).bindBlocking()

        assertEquals(listOf<MasterKdf>(kdf), usedKdfs)
        assertTrue(duration >= Duration.ZERO)
        assertTrue(keyBytes.all { it == 0.toByte() })
    }
}
