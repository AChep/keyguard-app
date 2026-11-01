package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupByteRange
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupDiagnostics
import com.artemchep.keyguard.common.service.backup.BackupObjectInfo
import com.artemchep.keyguard.common.service.backup.BackupObjectKey
import com.artemchep.keyguard.common.service.backup.BackupObjectStore
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreException
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupRepositoryZipImpl
import com.artemchep.keyguard.common.service.backup.BackupRunner
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.CountingDownloadTask
import com.artemchep.keyguard.common.service.backup.FakeDownloadAttachmentMetadata
import com.artemchep.keyguard.common.service.backup.FixedDateFormatter
import com.artemchep.keyguard.common.service.backup.ProbeFakeBackupObjectStore
import com.artemchep.keyguard.common.service.backup.testCipher
import com.artemchep.keyguard.common.service.download.asSourceLoader
import com.artemchep.keyguard.common.service.export.ExportVaultData
import com.artemchep.keyguard.common.service.export.ExportVaultDataService
import com.artemchep.keyguard.common.service.imports.impl.keyguardbackup.BackupImportServiceImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.util.zip.createZipService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/**
 * Backups written by the real [BackupRunner] into an in-memory store, read
 * back through the import.
 */
class BackupImportServiceTest {
    private val repository = BackupRepositoryZipImpl(
        json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
        zipService = createZipService(),
    )

    private val store = ProbeFakeBackupObjectStore()

    private val storeFactory = object : BackupObjectStoreFactory {
        override suspend fun open(
            store: BackupStoreConfig,
        ): BackupObjectStore = this@BackupImportServiceTest.store
    }

    private val service = BackupImportServiceImpl(
        storeFactory = storeFactory,
        backupRepository = repository,
        cryptoGenerator = NativeCryptoGenerator(),
        cipherEncryptor = NativeCipherEncryptor(
            cryptoGenerator = NativeCryptoGenerator(),
            base64Service = Base64ServiceImpl(),
        ),
    )

    @Test
    fun `encrypted backups restore native Keyguard metadata`() = runTest {
        backup(
            password = Password(PASSWORD),
            includeAttachments = false,
            exporter = object : ExportVaultDataService by StaticVaultExport {
                override fun exportJson(data: ExportVaultData): String = metadataExport()
            },
        )
        val opened = service.open(LOCATION, password = Password(PASSWORD))
        val backup = assertIs<ImportOpenResult.Success>(opened).source
        try {
            val plan = assertIs<ImportResult.Success>(backup.parse(backup.snapshots.single().id, IMPORT_TEST_NOW)).plan
            assertMetadataImported(plan)
        } finally {
            backup.close()
        }
    }

    @Test
    fun `an encrypted backup imports with its attachment`() = runTest {
        backup(password = Password(PASSWORD))

        assertEquals(
            ImportOpenResult.Failure(ImportError.PasswordRequired),
            service.open(LOCATION, password = null),
        )
        assertEquals(
            ImportOpenResult.Failure(ImportError.WrongPassword),
            service.open(LOCATION, password = Password("wrong")),
        )

        val opened = service.open(LOCATION, password = Password(PASSWORD))
        val backup = assertIs<ImportOpenResult.Success>(opened).source
        val snapshot = backup.snapshots.single()
        assertEquals(1, snapshot.itemCount)
        assertEquals(1, snapshot.attachmentCount)

        val plan = assertIs<ImportResult.Success>(backup.parse(snapshot.id, IMPORT_TEST_NOW)).plan
        assertEquals("Keyguard", plan.source.displayName)
        val item = plan.items.single()
        assertEquals("Cipher", item.request.title)
        val attachment = item.attachments.single()
        assertEquals("file.txt", attachment.name)

        val files = backup.extract(setOf(attachment.ref)) { _, source ->
            source.readString()
        }
        assertEquals("payload", files.getValue(attachment.ref))
        backup.close()
    }

    @Test
    fun `an unencrypted backup opens without a password`() = runTest {
        backup(password = null, includeAttachments = false)

        val opened = service.open(LOCATION, password = null)
        val backup = assertIs<ImportOpenResult.Success>(opened).source
        val snapshot = backup.snapshots.single()
        val plan = assertIs<ImportResult.Success>(backup.parse(snapshot.id, IMPORT_TEST_NOW)).plan
        // Listed by the vault, but the snapshot stored no file for it.
        assertTrue(plan.items.single().attachments.isEmpty())
        assertEquals(1, plan.skips[ImportSkipReason.Attachment])
    }

    @Test
    fun `a corrupt snapshot reports a parse error`() = runTest {
        backup(password = null, includeAttachments = false)
        val opened = assertIs<ImportOpenResult.Success>(service.open(LOCATION, password = null))
        val backup = opened.source
        try {
            val key = store.objects.keys.single { it.value.startsWith("snapshots/") }
            store.objects[key] = store.objects.getValue(key).copyOf(12)
            assertEquals(
                ImportResult.Failure(ImportError.Parse),
                backup.parse(backup.snapshots.single().id, IMPORT_TEST_NOW),
            )
        } finally {
            backup.close()
        }
    }

    @Test
    fun `a location without a backup is not one`() = runTest {
        assertEquals(
            ImportOpenResult.Failure(BackupImportError.NotFound),
            service.open(LOCATION, password = null),
        )
        assertEquals(1, store.closeCalls)
    }

    @Test
    fun `a cancelled open closes the location`() = runTest {
        val reading = CompletableDeferred<Unit>()
        // Never answers, so the open is still reading when it is cancelled.
        val stalled = object : BackupObjectStore by store {
            override suspend fun stat(
                key: BackupObjectKey,
            ): BackupObjectInfo? {
                reading.complete(Unit)
                awaitCancellation()
            }

            override suspend fun read(
                key: BackupObjectKey,
                range: BackupByteRange?,
            ): Source {
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        val service = BackupImportServiceImpl(
            storeFactory = object : BackupObjectStoreFactory {
                override suspend fun open(
                    store: BackupStoreConfig,
                ): BackupObjectStore = stalled
            },
            backupRepository = repository,
            cryptoGenerator = NativeCryptoGenerator(),
            cipherEncryptor = NativeCipherEncryptor(
                cryptoGenerator = NativeCryptoGenerator(),
                base64Service = Base64ServiceImpl(),
            ),
        )

        val job = launch {
            service.open(LOCATION, password = null)
        }
        reading.await()
        job.cancelAndJoin()
        assertEquals(1, store.closeCalls)
    }

    @Test
    fun `a backup without snapshots is empty`() = runTest {
        repository.getOrCreateMetadata(
            store = store,
            password = null,
            nowProvider = { IMPORT_TEST_NOW },
            repoIdProvider = { "repo-1" },
        )

        assertEquals(
            ImportOpenResult.Failure(BackupImportError.Empty),
            service.open(LOCATION, password = null),
        )
    }

    @Test
    fun `a location that rejects the credentials denies access`() = runTest {
        val service = BackupImportServiceImpl(
            storeFactory = object : BackupObjectStoreFactory {
                override suspend fun open(
                    store: BackupStoreConfig,
                ): BackupObjectStore = throw BackupObjectStoreException.AuthenticationFailed()
            },
            backupRepository = repository,
            cryptoGenerator = NativeCryptoGenerator(),
            cipherEncryptor = NativeCipherEncryptor(
                cryptoGenerator = NativeCryptoGenerator(),
                base64Service = Base64ServiceImpl(),
            ),
        )

        assertEquals(
            ImportOpenResult.Failure(BackupImportError.AccessDenied),
            service.open(LOCATION, password = null),
        )
    }

    private suspend fun backup(
        password: Password?,
        includeAttachments: Boolean = true,
        exporter: ExportVaultDataService = StaticVaultExport,
    ) {
        val runner = BackupRunner(
            exportVaultDataService = exporter,
            backupRepository = repository,
            backupObjectStoreFactory = storeFactory,
            cryptoGenerator = NativeCryptoGenerator(),
            base64Service = Base64ServiceImpl(),
            dateFormatter = FixedDateFormatter,
            downloadSourceLoader = CountingDownloadTask().asSourceLoader(),
            downloadAttachmentMetadata = FakeDownloadAttachmentMetadata,
            diagnostics = BackupDiagnostics.NoOp,
        )
        val result = runner.run(
            BackupConfig(
                enabled = true,
                store = LOCATION,
                password = password,
                includeAttachments = includeAttachments,
            ),
        )
        assertEquals(false, result.skipped)
    }
}

private const val PASSWORD = "backup password"

private val LOCATION = BackupStoreConfig.Local(
    path = "/tmp/keyguard-backups",
)

/**
 * The vault of [testCipher], as the exporter writes it: the item under its
 * remote id, listing its one remote attachment.
 */
private object StaticVaultExport : ExportVaultDataService {
    override suspend fun create(
        filter: DFilter,
    ): ExportVaultData = ExportVaultData(
        ciphers = listOf(testCipher()),
        folders = emptyList(),
        collections = emptyList(),
        organizations = emptyList(),
    )

    override fun exportJson(
        data: ExportVaultData,
    ): String = """
        {
          "encrypted": false,
          "items": [{
            "id": "remote-cipher-1", "type": 2, "name": "Cipher", "secureNote": {"type": 0},
            "attachments": [{"id": "attachment-1", "fileName": "file.txt", "size": 7}]
          }]
        }
    """.trimIndent()
}
