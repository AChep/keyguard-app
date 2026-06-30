package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ACCOUNT_ID
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestLogRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestServer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

internal val testBase32Service: Base32Service = Base32ServiceJvm()
internal val testBase64Service: Base64Service = Base64ServiceJvm()
internal val testCryptoGenerator = UploadTestCryptoGenerator
internal val testJson: Json = UploadTestServer.json

internal object TestLogRepository : LogRepository by UploadTestLogRepository

internal typealias TestVaultDatabaseManager = UploadTestVaultDatabaseManager

private val TEST_REVISION_DATE = Instant.parse("2024-01-01T00:00:00Z")

internal fun createTestDatabase(): Database = createUploadTestDatabase()

internal fun insertAccount(
    db: Database,
    accountId: String = ACCOUNT_ID,
) {
    db.accountQueries.insert(
        accountId = accountId,
        data = KeePassToken(
            id = accountId,
            key = KeePassToken.Key(
                passwordBase64 = testBase64Service.encodeToString("password"),
            ),
            database = KeePassToken.Database(
                fileName = "vault.kdbx",
                location = FileLocation.Local(
                    uri = "file:///vault.kdbx",
                    accessToken = null,
                    managedByApp = false,
                    displayName = "vault.kdbx",
                ),
            ),
        ),
    )
}

internal fun insertLocalCipher(
    db: Database,
    cipher: BitwardenCipher,
) {
    db.cipherQueries.insert(
        cipherId = cipher.cipherId,
        accountId = cipher.accountId,
        folderId = cipher.folderId,
        data = cipher,
        updatedAt = cipher.revisionDate,
    )
}

internal fun testBitwardenCipher(
    cipherId: String,
    name: String = "Cipher",
    accountId: String = ACCOUNT_ID,
) = BitwardenCipher(
    accountId = accountId,
    cipherId = cipherId,
    revisionDate = TEST_REVISION_DATE,
    service = BitwardenService(version = BitwardenService.VERSION),
    keyBase64 = "cipher-key",
    name = name,
    notes = "",
    favorite = false,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.SecureNote,
    secureNote = BitwardenCipher.SecureNote(),
)

internal fun testBitwardenFolder(
    folderId: String,
    name: String = "Folder",
    accountId: String = ACCOUNT_ID,
) = BitwardenFolder(
    accountId = accountId,
    folderId = folderId,
    revisionDate = TEST_REVISION_DATE,
    service = BitwardenService(version = BitwardenService.VERSION),
    name = name,
)

internal fun buildEntry(
    title: String = "",
    username: String = "",
    password: String = "",
    url: String = "",
    notes: String = "",
    extraFields: Map<String, EntryValue> = emptyMap(),
): Entry = Entry(
    uuid = Uuid.random(),
    fields = EntryFields.createDefault() +
            linkedMapOf(
                BasicField.Title() to EntryValue.Plain(title),
                BasicField.UserName() to EntryValue.Plain(username),
                BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(password)),
                BasicField.Url() to EntryValue.Plain(url),
                BasicField.Notes() to EntryValue.Plain(notes),
            ) +
            extraFields,
)
