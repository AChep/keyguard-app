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
}
