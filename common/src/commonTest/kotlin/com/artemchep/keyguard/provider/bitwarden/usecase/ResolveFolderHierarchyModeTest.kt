package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ResolveFolderHierarchyModeTest {
    @Test
    fun `bitwarden account uses path mode`() = runTest {
        val accountId = AccountId("bitwarden")
        val resolver = resolver(bitwardenToken(accountId.id))

        assertEquals(
            FolderHierarchyMode.Path,
            resolver(accountId).bind(),
        )
    }

    @Test
    fun `keepass account uses parent id mode`() = runTest {
        val accountId = AccountId("keepass")
        val resolver = resolver(keepassToken(accountId.id))

        assertEquals(
            FolderHierarchyMode.ParentId,
            resolver(accountId).bind(),
        )
    }

    @Test
    fun `missing account fails instead of guessing a mode`() = runTest {
        val resolver = resolver()

        assertFailsWith<IllegalStateException> {
            resolver(AccountId("missing")).bind()
        }
    }

    private fun resolver(
        vararg tokens: ServiceToken,
    ) = ResolveFolderHierarchyModeImpl(
        tokenRepository = FolderModeTokenRepository(tokens.toList()),
    )
}

private class FolderModeTokenRepository(
    private val tokens: List<ServiceToken>,
) : ServiceTokenRepository {
    override fun get(): Flow<List<ServiceToken>> = flowOf(tokens)

    override fun getById(id: AccountId): IO<ServiceToken?> = {
        tokens.firstOrNull { token -> token.id == id.id }
    }

    override fun put(model: ServiceToken): IO<Unit> = {
        error("Not used by this test.")
    }
}

private fun bitwardenToken(
    id: String,
) = BitwardenToken(
    id = id,
    key = BitwardenToken.Key(
        masterKeyBase64 = "",
        passwordKeyBase64 = "",
        encryptionKeyBase64 = "",
        macKeyBase64 = "",
    ),
    token = BitwardenToken.Token(
        refreshToken = "refresh",
        accessToken = "access",
        expirationDate = Instant.parse("2099-01-01T00:00:00Z"),
    ),
    user = BitwardenToken.User(email = "$id@example.com"),
    env = BitwardenToken.Environment(),
)

private fun keepassToken(
    id: String,
) = KeePassToken(
    id = id,
    key = KeePassToken.Key(
        passwordBase64 = "password",
    ),
    files = KeePassToken.Files(
        databaseUri = "file:///$id.kdbx",
    ),
)
