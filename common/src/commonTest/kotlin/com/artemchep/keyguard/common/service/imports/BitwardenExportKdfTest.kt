package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.service.imports.impl.bitwarden.bitwardenExportKdfOrNull
import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitwardenExportKdfTest {
    private val pbkdf2 = PreLogin.Hash.Pbkdf2
    private val argon2 = PreLogin.Hash.Argon2id

    @Test
    fun `pbkdf2 accepts the export range, below the login minimum`() {
        assertEquals(
            PreLogin.Hash.Pbkdf2(iterationsCount = 5_000),
            pbkdf2OrNull(5_000),
        )
        assertEquals(
            PreLogin.Hash.Pbkdf2(iterationsCount = pbkdf2.ITERATIONS_MAX),
            pbkdf2OrNull(pbkdf2.ITERATIONS_MAX),
        )
    }

    @Test
    fun `pbkdf2 out of range is refused`() {
        assertNull(pbkdf2OrNull(4_999))
        assertNull(pbkdf2OrNull(pbkdf2.ITERATIONS_MAX + 1))
        assertNull(pbkdf2OrNull(null))
    }

    @Test
    fun `argon2id accepts the bounds`() {
        assertEquals(
            PreLogin.Hash.Argon2id(
                iterationsCount = argon2.ITERATIONS_MAX,
                memoryMb = argon2.MEMORY_MB_MAX,
                parallelism = argon2.PARALLELISM_MAX,
            ),
            argon2OrNull(argon2.ITERATIONS_MAX, argon2.MEMORY_MB_MAX, argon2.PARALLELISM_MAX),
        )
    }

    @Test
    fun `argon2id out of range is refused`() {
        assertNull(argon2OrNull(argon2.ITERATIONS_MIN - 1, 16, 1))
        assertNull(argon2OrNull(argon2.ITERATIONS_MAX + 1, 16, 1))
        assertNull(argon2OrNull(2, argon2.MEMORY_MB_MIN - 1, 1))
        assertNull(argon2OrNull(2, argon2.MEMORY_MB_MAX + 1, 1))
        assertNull(argon2OrNull(2, 16, argon2.PARALLELISM_MIN - 1))
        assertNull(argon2OrNull(2, 16, argon2.PARALLELISM_MAX + 1))
        assertNull(argon2OrNull(2, null, 1))
    }

    @Test
    fun `unknown kdf type is refused`() {
        assertNull(bitwardenExportKdfOrNull(type = 2, iterations = 2, memoryMb = 16, parallelism = 1))
        assertNull(bitwardenExportKdfOrNull(type = null, iterations = 600_000, memoryMb = null, parallelism = null))
    }

    private fun pbkdf2OrNull(iterations: Int?) = bitwardenExportKdfOrNull(
        type = 0,
        iterations = iterations,
        memoryMb = null,
        parallelism = null,
    )

    private fun argon2OrNull(
        iterations: Int,
        memoryMb: Int?,
        parallelism: Int,
    ) = bitwardenExportKdfOrNull(
        type = 1,
        iterations = iterations,
        memoryMb = memoryMb,
        parallelism = parallelism,
    )
}
