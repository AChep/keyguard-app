package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.exception.credential.CredentialNotStoredException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasswordData
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class AddCredentialCipherImplTest {
    private val database = createUploadTestDatabase().also { it.insertUploadTestAccount(ACCOUNT_ID) }
    private val addCredential = AddCredentialCipherImpl(
        modifyCipherById = ModifyCipherById(
            ModifyDatabase(
                db = UploadTestVaultDatabaseManager(database),
                getCanWrite = object : GetCanWrite {
                    override fun invoke() = flowOf(true)
                },
                getWriteAccess = object : GetWriteAccess {
                    override fun invoke() = flowOf(true)
                },
                queueSyncById = object : QueueSyncById {
                    override fun invoke(accountId: AccountId) = io(Unit)
                },
                markBackupAsDirty = object : MarkBackupAsDirty {
                    override fun invoke(): IO<BackupStatus> = io(BackupStatus())
                },
            ),
        ),
        cryptoGenerator = CryptoGeneratorJvm(),
        base64Service = Base64ServiceJvm(),
        getPasswordStrength = UploadTestPasswordStrength,
    )

    @Test
    fun `a new passkey is stored in the login`() = runTest {
        insert(cipher(LOGIN_ID, BitwardenCipher.Login(uris = emptyList())))
        assertEquals(AddCredentialCipher.Result.Stored, addCredential(passkey(LOGIN_ID)).bind())
        val stored = database.cipherQueries.getByCipherId(LOGIN_ID).executeAsOne().data_
        assertEquals(listOf(CREDENTIAL_ID), stored.login?.fido2Credentials?.map { it.credentialId })
    }

    @Test
    fun `unavailable targets fail for both passwords and passkeys`() = runTest {
        insert(cipher(NOTE_ID, login = null))
        val stub = cipher("stub", BitwardenCipher.Login(uris = emptyList()))
        insert(stub.copy(service = stub.service.copy(error = BitwardenService.Error(
            code = BitwardenService.Error.CODE_DECODING_FAILED,
            revisionDate = CREATED_AT,
        ))))
        val before = database.cipherQueries.get().executeAsList()
        for (cipherId in listOf(NOTE_ID, "missing", "stub")) {
            for (request in listOf(passkey(cipherId), password(cipherId))) {
                assertFailsWith<CredentialNotStoredException>("item $cipherId") {
                    addCredential(request).bind()
                }
            }
        }
        assertEquals(before, database.cipherQueries.get().executeAsList())
    }

    @Test
    fun `an existing password is a successful no-op`() = runTest {
        insert(cipher(LOGIN_ID, BitwardenCipher.Login(uris = emptyList())))
        assertEquals(AddCredentialCipher.Result.Stored, addCredential(password(LOGIN_ID)).bind())
        val stored = database.cipherQueries.getByCipherId(LOGIN_ID).executeAsOne()
        assertEquals(AddCredentialCipher.Result.Unchanged, addCredential(password(LOGIN_ID)).bind())
        assertEquals(stored, database.cipherQueries.getByCipherId(LOGIN_ID).executeAsOne())
    }

    private fun insert(cipher: BitwardenCipher) {
        database.cipherQueries.insert(
            cipherId = cipher.cipherId,
            accountId = cipher.accountId,
            folderId = cipher.folderId,
            data = cipher,
            updatedAt = CREATED_AT,
        )
    }

    private fun cipher(
        id: String,
        login: BitwardenCipher.Login?,
    ) = BitwardenCipher(
        accountId = ACCOUNT_ID,
        cipherId = id,
        revisionDate = CREATED_AT,
        service = BitwardenService(version = BitwardenService.VERSION),
        name = "Example",
        notes = null,
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = if (login != null) BitwardenCipher.Type.Login else BitwardenCipher.Type.SecureNote,
        login = login,
    )

    private fun passkey(cipherId: String) = AddCredentialCipherRequest(
        cipherId = cipherId,
        data = AddCredentialCipherRequestPasskeyData(
            credentialId = CREDENTIAL_ID,
            keyType = "public-key",
            keyAlgorithm = "ECDSA",
            keyCurve = "P-256",
            keyValue = "AQID",
            rpId = "example.com",
            rpName = "Example",
            counter = 0,
            userHandle = "dXNlcg",
            userName = "alice",
            userDisplayName = "Alice",
            discoverable = true,
        ),
    )

    private fun password(cipherId: String) = AddCredentialCipherRequest(
        cipherId = cipherId,
        data = AddCredentialCipherRequestPasswordData(
            id = "alice",
            password = "password",
            callingAppInfo = AddCredentialCipherRequestPasswordData.CallingAppInfo(
                origin = "https://example.com",
                packageName = "com.example.app",
            ),
        ),
    )

    private companion object {
        const val ACCOUNT_ID = "account"
        const val LOGIN_ID = "login"
        const val NOTE_ID = "note"
        const val CREDENTIAL_ID = "123e4567-e89b-12d3-a456-426614174000"
        val CREATED_AT: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
