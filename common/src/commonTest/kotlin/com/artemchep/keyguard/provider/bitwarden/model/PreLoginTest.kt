package com.artemchep.keyguard.provider.bitwarden.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PreLoginTest {
    @Test
    fun `pbkdf2 rejects weak iterations`() {
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Pbkdf2(
                iterationsCount = 599999,
            )
        }
    }

    @Test
    fun `argon2id rejects non-positive parallelism`() {
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Argon2id(
                iterationsCount = 3,
                memoryMb = 64,
                parallelism = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Argon2id(
                iterationsCount = 3,
                memoryMb = 64,
                parallelism = -1,
            )
        }
    }

    @Test
    fun `pbkdf2 rejects excessive iterations`() {
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Pbkdf2(
                iterationsCount = PreLogin.Hash.Pbkdf2.ITERATIONS_MAX + 1,
            )
        }
    }

    @Test
    fun `argon2id rejects excessive iterations`() {
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Argon2id(
                iterationsCount = PreLogin.Hash.Argon2id.ITERATIONS_MAX + 1,
                memoryMb = 64,
                parallelism = 4,
            )
        }
    }

    @Test
    fun `argon2id rejects excessive memory`() {
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Argon2id(
                iterationsCount = 3,
                memoryMb = PreLogin.Hash.Argon2id.MEMORY_MB_MAX + 1,
                parallelism = 4,
            )
        }
    }

    @Test
    fun `argon2id rejects excessive parallelism`() {
        assertFailsWith<IllegalArgumentException> {
            PreLogin.Hash.Argon2id(
                iterationsCount = 3,
                memoryMb = 64,
                parallelism = PreLogin.Hash.Argon2id.PARALLELISM_MAX + 1,
            )
        }
    }
}
