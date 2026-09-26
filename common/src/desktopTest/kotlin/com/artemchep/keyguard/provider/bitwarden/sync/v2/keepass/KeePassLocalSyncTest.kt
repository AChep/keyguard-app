package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import app.keemobile.kotpass.constants.BasicField
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.prepareKeePassDatabase
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.upload.FailingPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeePassLocalSyncTest {
    @Test
    fun `local sync initializes the profile and publishes later edits to the kdbx file`() = runTest {
        val directory = Files.createTempDirectory("keepass-local-sync").toFile()
        try {
            val file = directory.resolve("vault.kdbx")
            val uri = file.toURI().toString()
            val fileService = FileServiceImpl()
            val db = createTestDatabase()
            prepareKeePassDatabase(
                fileService = fileService,
                params = AddKeePassAccountParams(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = uri,
                    dbFileName = file.name,
                    keyUri = null,
                    password = "password",
                ),
            )
            // This is the persisted state after a queued account add, including
            // accounts left without a profile by older Android versions.
            val token = insertAccount(db, accountId = "local-account", fileName = file.name, uri = uri)
            val coordinator = createOfflineCoordinator(db, fileService)

            suspend fun readEntries() = openKeePassDatabase(
                token = token,
                fileService = fileService,
                base64Service = testBase64Service,
            ).content.group.entries

            assertNull(db.profileQueries.getByAccountId(token.id).executeAsOneOrNull())
            coordinator.sync(token)
            assertEquals(
                "Keyguard database",
                db.profileQueries.getByAccountId(token.id).executeAsOne().data_.name,
            )

            val cipherId = "11111111-1111-1111-1111-111111111111"
            val cipher = testBitwardenCipher(cipherId = cipherId, accountId = token.id, name = "Created offline")
            insertLocalCipher(db, cipher)
            coordinator.sync(token)
            assertEquals("Created offline", readEntries().single().fields[BasicField.Title()]?.content)

            val syncedCipher = db.cipherQueries.getByCipherId(cipherId).executeAsOne().data_
            insertLocalCipher(
                db,
                syncedCipher.copy(
                    name = "Edited offline",
                    revisionDate = syncedCipher.revisionDate + 1.seconds,
                ),
            )
            coordinator.sync(token)
            assertEquals("Edited offline", readEntries().single().fields[BasicField.Title()]?.content)

            val editedCipher = db.cipherQueries.getByCipherId(cipherId).executeAsOne().data_
            insertLocalCipher(
                db,
                editedCipher.copy(
                    service = editedCipher.service.copy(deleted = true),
                    revisionDate = editedCipher.revisionDate + 1.seconds,
                ),
            )
            coordinator.sync(token)
            assertTrue(readEntries().isEmpty())

            assertTrue(file.delete())
            assertFails { coordinator.sync(token) }
            assertIs<BitwardenMeta.LastSyncResult.Failure>(
                db.metaQueries.getByAccountId(token.id).executeAsOne().data_.lastSyncResult,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /** No network transport is available to this coordinator. */
    private fun createOfflineCoordinator(
        db: Database,
        fileService: FileServiceImpl,
    ) = KeePassSyncCoordinator(
        logRepository = TestLogRepository,
        cryptoGenerator = testCryptoGenerator,
        base32Service = testBase32Service,
        base64Service = testBase64Service,
        fileService = fileService,
        getPasswordStrength = UploadTestPasswordStrength,
        json = testJson,
        db = TestVaultDatabaseManager(db),
        pendingUploadCoordinator = FailingPendingUploadCoordinator,
        gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
    )
}
