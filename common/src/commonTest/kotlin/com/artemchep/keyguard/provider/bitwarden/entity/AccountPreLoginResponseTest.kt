package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountPreLoginResponseTest {
    @Test
    fun `new kdf settings take precedence over legacy fields`() {
        val response = AccountPreLoginResponse(
            kdfType = 1,
            kdfIterationsCount = 3,
            kdfMemory = 64,
            kdfParallelism = 4,
            kdfSettings = AccountPreLoginResponse.KdfSettings(
                kdfType = 0,
                iterations = 600000,
            ),
            salt = "user@example.com",
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals(
            PreLogin.Hash.Pbkdf2(
                iterationsCount = 600000,
            ),
            prelogin.hash,
        )
        assertEquals("user@example.com", prelogin.salt)
    }

    @Test
    fun `new argon2id kdf settings map to domain`() {
        val response = AccountPreLoginResponse(
            kdfSettings = AccountPreLoginResponse.KdfSettings(
                kdfType = 1,
                iterations = 5,
                memory = 128,
                parallelism = 2,
            ),
            salt = "custom-salt",
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals(
            PreLogin.Hash.Argon2id(
                iterationsCount = 5,
                memoryMb = 128,
                parallelism = 2,
            ),
            prelogin.hash,
        )
        assertEquals("custom-salt", prelogin.salt)
    }

    @Test
    fun `legacy fields still map to domain`() {
        val response = AccountPreLoginResponse(
            kdfType = 0,
            kdfIterationsCount = 100000,
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals(
            PreLogin.Hash.Pbkdf2(
                iterationsCount = 100000,
            ),
            prelogin.hash,
        )
        assertEquals("user@example.com", prelogin.salt)
    }

    @Test
    fun `missing argon2id parameters use bitwarden defaults`() {
        val response = AccountPreLoginResponse(
            kdfSettings = AccountPreLoginResponse.KdfSettings(
                kdfType = 1,
            ),
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals(
            PreLogin.Hash.Argon2id(
                iterationsCount = 3,
                memoryMb = 64,
                parallelism = 4,
            ),
            prelogin.hash,
        )
        assertEquals("user@example.com", prelogin.salt)
    }

    @Test
    fun `negative argon2id parallelism is rejected`() {
        val response = AccountPreLoginResponse(
            kdfSettings = AccountPreLoginResponse.KdfSettings(
                kdfType = 1,
                iterations = 3,
                memory = 64,
                parallelism = -1,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            response.toDomain("user@example.com")
        }
    }

    @Test
    fun `blank server salt uses normalized prelogin email`() {
        val response = AccountPreLoginResponse(
            kdfType = 0,
            salt = " ",
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals("user@example.com", prelogin.salt)
    }

    @Test
    fun `server salt different from email is preserved`() {
        val response = AccountPreLoginResponse(
            kdfType = 0,
            salt = "stored-salt",
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals("stored-salt", prelogin.salt)
    }

    @Test
    fun `new response json decodes from bitwarden server shape`() {
        val response = Json.decodeFromString<AccountPreLoginResponse>(
            """
            {
              "Kdf": 0,
              "KdfIterations": 600000,
              "KdfSettings": {
                "KdfType": 1,
                "Iterations": 5,
                "Memory": 128,
                "Parallelism": 2
              },
              "Salt": "stored-salt"
            }
            """.trimIndent(),
        )

        val prelogin = response.toDomain("user@example.com")

        assertEquals(
            PreLogin.Hash.Argon2id(
                iterationsCount = 5,
                memoryMb = 128,
                parallelism = 2,
            ),
            prelogin.hash,
        )
        assertEquals("stored-salt", prelogin.salt)
    }
}
