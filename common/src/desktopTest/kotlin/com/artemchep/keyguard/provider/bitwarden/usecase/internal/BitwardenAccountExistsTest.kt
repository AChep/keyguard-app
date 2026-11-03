package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.exception.AccountAlreadyExistsException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BitwardenAccountExistsTest {
    private val repository = repository(
        token(email = "user@example.com"),
    )

    @Test
    fun `rejects the same email on the same server`() = runTest {
        assertFailsWith<AccountAlreadyExistsException> {
            repository.requireNewAccount(" User@Example.com ", ServerEnv())
        }
    }

    @Test
    fun `rejects the same email on the same server with a trailing slash`() = runTest {
        val repository = repository(
            token(email = "user@example.com", env = ServerEnv(baseUrl = "https://vault.example.com")),
        )
        assertFailsWith<AccountAlreadyExistsException> {
            repository.requireNewAccount("user@example.com", ServerEnv(baseUrl = "https://Vault.example.com/"))
        }
    }

    @Test
    fun `accepts the same email on another server`() = runTest {
        repository.requireNewAccount("user@example.com", ServerEnv(region = ServerEnv.Region.EU))
        repository.requireNewAccount("user@example.com", ServerEnv(baseUrl = "https://vault.example.com"))
    }

    @Test
    fun `accepts another email on the same server`() = runTest {
        repository.requireNewAccount("other@example.com", ServerEnv())
    }

    private fun repository(
        vararg tokens: BitwardenToken,
    ) = object : BitwardenTokenRepository {
        override fun get(): Flow<List<BitwardenToken>> = flowOf(tokens.toList())

        override fun getById(id: AccountId): IO<BitwardenToken?> =
            io(tokens.firstOrNull { it.id == id.id })

        override fun put(model: BitwardenToken): IO<Unit> = io(Unit)
    }

    private fun token(
        email: String,
        env: ServerEnv = ServerEnv(),
    ) = BitwardenToken(
        key = BitwardenToken.Key(
            masterKeyBase64 = "",
            passwordKeyBase64 = "",
            encryptionKeyBase64 = "",
            macKeyBase64 = "",
        ),
        user = BitwardenToken.User(email = email),
        env = BitwardenToken.Environment.of(env),
    )
}
