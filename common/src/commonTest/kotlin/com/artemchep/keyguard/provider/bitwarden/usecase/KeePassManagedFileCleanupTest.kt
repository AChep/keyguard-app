package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.feature.auth.companion.CompanionKeePassPayload
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionKeePassAccountImpl
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeePassManagedFileCleanupTest {
    @Test
    fun `cleanup deletes owned keepass file and parent directory`() {
        val deletedUris = mutableListOf<String>()
        val fileService = TestFileService(
            onDelete = { uri ->
                deletedUris += uri
                true
            },
        )

        cleanupManagedKeePassFiles(
            fileService = fileService,
            tokens = listOf(
                keePassToken(
                    databaseUri = "file:///managed/request/database.kdbx",
                    managedByApp = true,
                ),
            ),
        )

        assertEquals(
            listOf(
                "file:///managed/request/database.kdbx",
                "file:///managed/request",
            ),
            deletedUris,
        )
    }

    @Test
    fun `cleanup leaves unmanaged keepass file untouched`() {
        val deletedUris = mutableListOf<String>()
        val fileService = TestFileService(
            onDelete = { uri ->
                deletedUris += uri
                true
            },
        )

        cleanupManagedKeePassFiles(
            fileService = fileService,
            tokens = listOf(
                keePassToken(
                    databaseUri = "file:///external/database.kdbx",
                    managedByApp = false,
                ),
            ),
        )

        assertTrue(deletedUris.isEmpty())
    }

    @Test
    fun `companion keepass import marks staged database as managed`() = runTest {
        var submittedParams: AddKeePassAccountParams? = null
        val addKeePassAccount = object : AddKeePassAccount {
            override fun invoke(params: AddKeePassAccountParams) = io(AccountId("account-id"))
                .also {
                submittedParams = params
            }
        }
        val useCase = ImportCompanionKeePassAccountImpl(
            addKeePassAccount = addKeePassAccount,
        )

        useCase(
            ImportCompanionKeePassAccount.Params(
                payload = CompanionKeePassPayload(
                    databaseFileName = "vault.kdbx",
                    keyFileName = "vault.key",
                    password = "secret",
                ),
                databaseUri = "file:///managed/request/database.kdbx",
                keyUri = "file:///temp/request/database.key",
            ),
        ).bind()

        val params = requireNotNull(submittedParams)
        assertEquals("file:///managed/request/database.kdbx", params.dbUri)
        assertEquals("vault.kdbx", params.dbFileName)
        assertEquals("file:///temp/request/database.key", params.keyUri)
        assertTrue(params.managedByApp)
    }
}

private fun keePassToken(
    databaseUri: String,
    managedByApp: Boolean,
): ServiceToken = KeePassToken(
    id = "account-id",
    key = KeePassToken.Key(
        passwordBase64 = "password",
    ),
    files = KeePassToken.Files(
        databaseUri = databaseUri,
        databaseFileName = "vault.kdbx",
        managedByApp = managedByApp,
    ),
)

private class TestFileService(
    private val onDelete: (String) -> Boolean = { true },
) : FileService {
    override fun exists(uri: String): Boolean = false

    override fun readFromFile(uri: String): Source = error("Not used in this test.")

    override fun writeToFile(uri: String): Sink = error("Not used in this test.")

    override fun delete(uri: String): Boolean = onDelete(uri)
}
