package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.exception.PremiumException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.metadataExport
import com.artemchep.keyguard.common.service.imports.metadataPasskey
import com.artemchep.keyguard.common.service.imports.parseImportPlan
import com.artemchep.keyguard.common.service.imports.lastPassNoteCsv
import com.artemchep.keyguard.common.service.imports.lastPassSshNote
import com.artemchep.keyguard.crypto.NativeSshKeyImportService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestCipherCodec
import com.artemchep.keyguard.common.service.sshagent.certificateTestProcessor
import com.artemchep.keyguard.common.service.sshagent.certificateSignRequest
import com.artemchep.keyguard.common.service.sshagent.unlockedVaultSession
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.crypto.NativeSshCertificateParser
import kotlin.test.assertIs
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_USER_CERTIFICATE
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import kotlin.test.assertNull
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.ArchiveCipherById
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.usecase.createCiphers
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestMarkBackupAsDirty
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.FailingEncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.impl.PendingUploadCoordinatorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A save takes over the files its caller staged: it keeps them if it writes
 * the cipher, and deletes them if it fails before that, however it fails.
 */
@Suppress("TooManyFunctions")
class AddCipherStagedAttachmentTest {
    @Test
    fun `LastPass native mappings survive vault save and KeePass round trip`() = runTest {
        val key = requireNotNull(javaClass.getResourceAsStream("/ssh-import-corpus/openssh/id_ed25519"))
            .bufferedReader().use { it.readText() }.trimEnd()
        val notes = listOf(
            lastPassSshNote(key, passphrase = "   "),
            "NoteType:Credit Card\nExpiration Date:January,\nNotes:",
            "NoteType:Credit Card\nExpiration Date:,2029\nNotes:",
        )
        val requests = notes.mapIndexed { index, note ->
            parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv(note),
                sshKeyImportService = NativeSshKeyImportService,
            ).items.single().request.copy(
                title = "LastPass $index",
                ownership2 = CreateRequest.Ownership2("account", FolderInfo.None, null, emptySet()),
            )
        }
        Fixture().use { fixture ->
            fixture.create(*requests.toTypedArray())
            val stored = fixture.rows().map { it.data_ }.sortedBy { it.name }
            assertEquals(requests[0].sshKey.privateKey, stored[0].sshKey?.privateKey)
            assertEquals(requests[0].sshKey.publicKey, stored[0].sshKey?.publicKey)
            assertEquals(requests[0].sshKey.fingerprint, stored[0].sshKey?.fingerprint)
            assertEquals("   ", stored[0].fields.single { it.name == "Passphrase" }.value)
            assertEquals("1", stored[1].card?.expMonth)
            assertNull(stored[1].card?.expYear)
            assertNull(stored[2].card?.expMonth)
            assertEquals("2029", stored[2].card?.expYear)

            val codec = createTestCipherCodec()
            for (cipher in stored) {
                val encoded = codec.encode(cipher, remote = null, existingBinaries = emptyMap())
                val decoded = codec.decode(
                    accountId = cipher.accountId,
                    folderId = null,
                    cipherId = cipher.cipherId,
                    remote = encoded.entry,
                    local = null,
                    revisionDate = cipher.revisionDate,
                    binaries = encoded.binaryAdditions,
                )
                assertEquals(cipher.type, decoded.type)
                assertEquals(cipher.sshKey, decoded.sshKey)
                assertEquals(cipher.card, decoded.card)
                if (cipher.sshKey != null) {
                    assertEquals("   ", decoded.fields.single { it.name == "Passphrase" }.value)
                }
            }
        }
    }

    @Test
    fun `exported metadata is available in the saved vault before sync`() = runTest {
        val plan = parseImportPlan(ImportFormatId.Bitwarden, metadataExport())
        Fixture().use { fixture ->
            val requests = plan.items.map { item ->
                item.request.copy(ownership2 = CreateRequest.Ownership2("account", FolderInfo.None, null, emptySet()))
            }
            fixture.create(*requests.toTypedArray())
            val stored = fixture.rows().map { it.data_ }
            val ssh = stored.single { it.type == BitwardenCipher.Type.SshKey }
            assertEquals(TEST_ED25519_USER_CERTIFICATE, ssh.sshKey?.certificate)
            assertEquals(KeePassIcon.TerminalEncrypted, ssh.customIcon)
            assertEquals(listOf("native", "transport"), ssh.tags.map { it.name })
            val processor = certificateTestProcessor(
                unlockedVaultSession(ssh.toDomain(UploadTestPasswordStrength)),
                parser = NativeSshCertificateParser,
            )
            val signed = processor.signData(certificateSignRequest(TEST_ED25519_USER_CERTIFICATE))
            val success = assertIs<SshAgentRequestProcessor.SignDataResult.Success>(signed)
            assertEquals(64, success.response.signature.size)
            val passkey = stored.single { it.type == BitwardenCipher.Type.Login }.login!!.fido2Credentials.single()
            assertEquals(metadataPasskey("first"), passkey.toDomain())
        }
    }

    @Test
    fun `edits preserve an unspecified icon and can replace or clear it`() = runTest {
        Fixture().use { fixture ->
            fixture.create(request().copy(customIcon = KeePassIcon.World))
            val id = fixture.rows().single().cipherId
            fixture.save(mapOf(id to request().copy(title = "Edited")))
            assertEquals(KeePassIcon.World, fixture.rows().single().data_.customIcon)
            fixture.save(mapOf(id to request().copy(customIcon = KeePassIcon.Warning)))
            assertEquals(KeePassIcon.Warning, fixture.rows().single().data_.customIcon)
            fixture.save(mapOf(id to request().copy(customIcon = KeePassIcon.Key)))
            assertNull(fixture.rows().single().data_.customIcon)
        }
    }

    @Test
    fun `a new cipher is created archived and trashed in one write and edits keep both`() = runTest {
        Fixture().use { fixture ->
            val archivedDate = Instant.fromEpochSeconds(2_000L)
            val deletedDate = Instant.fromEpochSeconds(3_000L)
            var mutations = 0
            fixture.afterMutation = { mutations++ }

            fixture.create(
                request().copy(title = "Archived", archivedDate = archivedDate),
                request().copy(title = "Trashed", deletedDate = deletedDate),
                request().copy(title = "Live"),
            )

            assertEquals(1, mutations)
            val stored = fixture.rows().associate { it.data_.name to it.data_ }
            assertEquals(archivedDate, stored.getValue("Archived").archivedDate)
            assertNull(stored.getValue("Archived").deletedDate)
            assertEquals(deletedDate, stored.getValue("Trashed").deletedDate)
            assertNull(stored.getValue("Trashed").archivedDate)
            assertNull(stored.getValue("Live").archivedDate)
            assertNull(stored.getValue("Live").deletedDate)

            val ids = stored.mapValues { it.value.cipherId }
            fixture.save(
                mapOf(
                    ids.getValue("Archived") to request().copy(title = "Archived"),
                    ids.getValue("Trashed") to request().copy(title = "Trashed"),
                ),
            )
            val edited = fixture.rows().associate { it.data_.name to it.data_ }
            assertEquals(archivedDate, edited.getValue("Archived").archivedDate)
            assertEquals(deletedDate, edited.getValue("Trashed").deletedDate)
        }
    }

    @Test
    fun `a saved cipher keeps the file the caller staged`() = runTest {
        Fixture().use { fixture ->
            val staged = fixture.staged("attachment-1")

            fixture.create(request(staged))

            val attachment = fixture.rows().single().data_.attachments.single()
            assertEquals(staged.file, (attachment as BitwardenCipher.Attachment.Local).pendingUpload)
            fixture.assertSavedFiles(setOf(staged.file.path), cipherCount = 1)
        }
    }

    @Test
    fun `a staged file is deleted when an attachment before it fails to stage`() = runTest {
        Fixture().use { fixture ->
            val staged = fixture.staged("attachment-1")
            val failure = IllegalStateException("Cannot read attachment")
            fixture.uploads.beforeStage = { throw failure }

            val error = assertFailsWith<IllegalStateException> {
                fixture.create(request(local("missing"), staged))
            }

            assertSame(failure, error)
            fixture.assertDiscarded(setOf(staged.file.path))
        }
    }

    @Test
    fun `a staged file is deleted when building the cipher fails`() = runTest {
        Fixture().use { fixture ->
            val staged = fixture.staged("attachment-1")

            assertFailsWith<IllegalArgumentException> {
                fixture.create(request(staged).withInvalidBoolean())
            }

            fixture.assertDiscarded(setOf(staged.file.path))
        }
    }

    @Test
    fun `a staged file is deleted when the vault refuses the write`() = runTest {
        Fixture(canWrite = false).use { fixture ->
            val staged = fixture.staged("attachment-1")

            assertFailsWith<PremiumException> { fixture.create(request(staged)) }

            fixture.assertDiscarded(setOf(staged.file.path))
        }
    }

    @Test
    fun `a staged file is deleted when the save is cancelled before the database is available`() = runTest {
        Fixture().use { fixture ->
            val staged = fixture.staged("attachment-1")
            val waiting = CompletableDeferred<Unit>()
            fixture.beforeMutation = {
                waiting.complete(Unit)
                awaitCancellation()
            }

            val job = launch { fixture.create(request(staged)) }
            waiting.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            fixture.assertDiscarded(setOf(staged.file.path))
        }
    }

    @Test
    fun `folder creation failure deletes staged files from every request`() = runTest {
        Fixture().use { fixture ->
            val first = fixture.staged("first")
            val later = fixture.staged("later")
            val failure = IllegalStateException("Cannot create folder")
            fixture.createFolders = { throw failure }
            val requestWithFolder = request(first).copy(
                ownership2 = CreateRequest.Ownership2("account", FolderInfo.New("Imported"), null, emptySet()),
            )

            val error = assertFailsWith<IllegalStateException> {
                fixture.create(requestWithFolder, request(later))
            }

            assertSame(failure, error)
            assertTrue(fixture.uploads.stagedSources.isEmpty())
            fixture.assertDiscarded(setOf(first.file.path, later.file.path))
        }
    }

    @Test
    fun `invalid later cipher deletes prepared uploads and unvisited staged files`() = runTest {
        Fixture().use { fixture ->
            val first = fixture.staged("first")
            val invalid = fixture.staged("invalid")
            val unvisited = fixture.staged("unvisited")
            val local = local("first-local")

            assertFailsWith<IllegalArgumentException> {
                fixture.create(
                    request(local, first),
                    request(invalid).withInvalidBoolean(),
                    request(unvisited),
                )
            }

            assertEquals(listOf(local.uri.toString()), fixture.uploads.stagedSources)
            fixture.assertDiscarded(
                setOf(first.file.path, invalid.file.path, unvisited.file.path) + fixture.uploads.stagedPaths,
            )
        }
    }

    @Test
    fun `later staging failure cleans earlier requests and the partially prepared request`() = runTest {
        Fixture().use { fixture ->
            val first = fixture.staged("first")
            val second = fixture.staged("second")
            val unvisited = fixture.staged("unvisited")
            val firstLocal = local("first-local")
            val secondLocal = local("second-local")
            val missing = local("missing")
            val failure = IllegalStateException("Cannot read later attachment")
            fixture.uploads.beforeStage = { uri ->
                if (uri == missing.uri.toString()) throw failure
            }

            val error = assertFailsWith<IllegalStateException> {
                fixture.create(
                    request(first, firstLocal),
                    request(secondLocal, missing, second),
                    request(unvisited),
                )
            }

            assertSame(failure, error)
            assertEquals(
                listOf(firstLocal.uri.toString(), secondLocal.uri.toString()),
                fixture.uploads.stagedSources,
            )
            fixture.assertDiscarded(
                setOf(first.file.path, second.file.path, unvisited.file.path) + fixture.uploads.stagedPaths,
            )
        }
    }

    @Test
    fun `cancellation during a later request deletes all uncommitted uploads`() = runTest {
        Fixture().use { fixture ->
            val first = fixture.staged("first")
            val second = fixture.staged("second")
            val unvisited = fixture.staged("unvisited")
            val firstLocal = local("first-local")
            val secondLocal = local("second-local")
            val blocked = local("blocked")
            val waiting = CompletableDeferred<Unit>()
            fixture.uploads.beforeStage = { uri ->
                if (uri == blocked.uri.toString()) {
                    waiting.complete(Unit)
                    awaitCancellation()
                }
            }

            val job = launch {
                fixture.create(
                    request(first, firstLocal),
                    request(secondLocal, blocked, second),
                    request(unvisited),
                )
            }
            waiting.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(
                listOf(firstLocal.uri.toString(), secondLocal.uri.toString()),
                fixture.uploads.stagedSources,
            )
            fixture.assertDiscarded(
                setOf(first.file.path, second.file.path, unvisited.file.path) + fixture.uploads.stagedPaths,
            )
        }
    }

    @Test
    fun `a failed transaction rolls back the batch and preserves previously saved attachments`() = runTest {
        Fixture().use { fixture ->
            val old = fixture.staged("old")
            val oldId = fixture.create(request(old)).single()
            val originalRows = fixture.rows()
            val replacement = fixture.staged("replacement")
            val last = fixture.staged("last")
            fixture.driver.execute(
                identifier = null,
                sql = """
                    CREATE TRIGGER reject_later_cipher
                    BEFORE INSERT ON cipher
                    WHEN (SELECT COUNT(*) FROM cipher) = 2
                    BEGIN
                        SELECT RAISE(ABORT, 'forced later insert failure');
                    END;
                """.trimIndent(),
                parameters = 0,
            )

            val error = assertFailsWith<SQLException> {
                fixture.save(
                    linkedMapOf(
                        oldId to request(replacement),
                        "new-first" to request(local("first-local")),
                        "new-last" to request(last),
                    ),
                )
            }

            assertTrue(error.message.orEmpty().contains("forced later insert failure"))
            assertEquals(originalRows, fixture.rows())
            assertEquals(setOf(old.file.path), fixture.uploads.existingPaths)
            assertEquals(
                setOf(replacement.file.path, last.file.path) + fixture.uploads.stagedPaths,
                fixture.uploads.deletedPaths,
            )
        }
    }

    @Test
    fun `an exception after commit keeps every saved attachment`() = runTest {
        Fixture().use { fixture ->
            val staged = fixture.staged("first")
            val failure = IllegalStateException("Failure after commit")
            fixture.afterMutation = { throw failure }

            val error = assertFailsWith<IllegalStateException> {
                fixture.create(request(staged), request(local("second-local")))
            }

            assertSame(failure, error)
            fixture.assertSavedFiles(setOf(staged.file.path) + fixture.uploads.stagedPaths, cipherCount = 2)
        }
    }

    @Test
    fun `cancellation after commit keeps every saved attachment`() = runTest {
        Fixture().use { fixture ->
            val staged = fixture.staged("first")
            val committed = CompletableDeferred<Unit>()
            fixture.afterMutation = {
                committed.complete(Unit)
                awaitCancellation()
            }

            val job = launch { fixture.create(request(staged), request(local("second-local"))) }
            committed.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            fixture.assertSavedFiles(setOf(staged.file.path) + fixture.uploads.stagedPaths, cipherCount = 2)
        }
    }

    private fun local(id: String) = CreateRequest.Attachment.Local(
        id = id,
        uri = leParseUri("file:///tmp/$id.pdf"),
        name = "$id.pdf",
    )

    private fun CreateRequest.withInvalidBoolean() = copy(
        fields = persistentListOf(DSecret.Field(name = "Flag", value = "yes", type = DSecret.Field.Type.Boolean)),
    )

    private fun request(
        vararg attachments: CreateRequest.Attachment,
    ) = CreateRequest(
        ownership2 = CreateRequest.Ownership2("account", FolderInfo.None, null, emptySet()),
        title = "Report",
        type = DSecret.Type.SecureNote,
        favorite = false,
        reprompt = false,
        attachments = persistentListOf(*attachments),
        now = Instant.fromEpochSeconds(1_000L),
    )

    private class Fixture(
        canWrite: Boolean = true,
    ) : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = createUploadTestDatabase(driver.also { Database.Schema.create(it) })

        init { database.insertUploadTestAccount("account") }

        val uploads = RecordingEncryptedFilePendingUploadService()
        var beforeMutation: suspend () -> Unit = {}
        var afterMutation: suspend () -> Unit = {}
        var createFolders: suspend (Collection<AddFolderRequest>) -> List<String> = { error("Unexpected folder") }

        private val cryptoGenerator = object : CryptoGenerator by UploadTestCryptoGenerator {
            private var nextId = 0

            override fun uuid(): String = "generated-${++nextId}"
        }
        private val manager = object : VaultDatabaseManager by UploadTestVaultDatabaseManager(database) {
            override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> = ioEffect {
                beforeMutation()
                block(database).also { afterMutation() }
            }
        }
        private val modifyDatabase = ModifyDatabase(
            db = manager,
            getCanWrite = object : GetCanWrite { override fun invoke() = flowOf(canWrite) },
            getWriteAccess = object : GetWriteAccess { override fun invoke() = flowOf(true) },
            queueSyncById = object : QueueSyncById { override fun invoke(accountId: AccountId) = io(Unit) },
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
        )
        private val useCase = AddCipherImpl(
            modifyDatabase = modifyDatabase,
            addFolder = object : AddFolder {
                override fun invoke(requests: Collection<AddFolderRequest>): IO<List<String>> =
                    ioEffect { createFolders(requests) }
            },
            resolveFolderHierarchyMode = object : ResolveFolderHierarchyMode {
                override fun invoke(accountId: AccountId) = io(FolderHierarchyMode.Path)
            },
            archiveCipherById = object : ArchiveCipherById {
                override fun invoke(ids: Set<String>) = io(Unit)
            },
            trashCipherById = object : TrashCipherById {
                override fun invoke(ids: Set<String>) = io(Unit)
            },
            cryptoGenerator = cryptoGenerator,
            getPasswordStrength = UploadTestPasswordStrength,
            gpgKeyMetadataResolver = null,
            base64Service = Base64ServiceJvm(),
            pendingUploadCoordinator = PendingUploadCoordinatorImpl(uploads),
        )

        fun staged(id: String): CreateRequest.Attachment.Staged {
            val file = PendingUploadFile("/tmp/$id.bin", plainSize = 6L, encryptedSize = 64L)
            uploads.existingPaths += file.path
            return CreateRequest.Attachment.Staged(
                id = id,
                name = "$id.pdf",
                keyBase64 = Base64ServiceJvm().encodeToString(ByteArray(64)),
                file = file,
            )
        }

        suspend fun create(vararg requests: CreateRequest) =
            useCase.createCiphers(requests.toList(), cryptoGenerator).bind()

        suspend fun save(requests: Map<String?, CreateRequest>) = useCase(requests).bind()

        fun rows() = database.cipherQueries.get().executeAsList()

        fun assertDiscarded(expectedPaths: Set<String>) {
            assertEquals(expectedPaths, uploads.deletedPaths)
            assertTrue(uploads.existingPaths.isEmpty())
            assertTrue(rows().isEmpty())
        }

        fun assertSavedFiles(expectedPaths: Set<String>, cipherCount: Int) {
            val rows = rows()
            assertEquals(cipherCount, rows.size)
            val savedPaths = rows.flatMap { row ->
                row.data_.attachments.map { attachment ->
                    requireNotNull((attachment as BitwardenCipher.Attachment.Local).pendingUpload).path
                }
            }.toSet()
            assertEquals(expectedPaths, savedPaths)
            assertEquals(expectedPaths, uploads.existingPaths)
            assertTrue(uploads.deletedPaths.isEmpty())
        }

        override fun close() { driver.close() }
    }

    /** Models file lifetime while the real coordinator owns persistence and cleanup. */
    private class RecordingEncryptedFilePendingUploadService :
        EncryptedFilePendingUploadService by FailingEncryptedFilePendingUploadService {
        val existingPaths = mutableSetOf<String>()
        val stagedPaths = mutableSetOf<String>()
        val stagedSources = mutableListOf<String>()
        val deletedPaths = mutableSetOf<String>()
        var beforeStage: suspend (String) -> Unit = {}

        override suspend fun stage(
            accountId: String,
            namespace: String,
            fileId: String,
            sourceUri: String,
            fileKey: ByteArray,
        ): PendingUploadFile {
            beforeStage(sourceUri)
            val file = PendingUploadFile(
                path = "/tmp/$accountId/$namespace/$fileId.bin",
                plainSize = 6L,
                encryptedSize = 64L,
            )
            existingPaths += file.path
            stagedPaths += file.path
            stagedSources += sourceUri
            return file
        }

        override suspend fun delete(pendingUpload: PendingUploadFile) {
            // Like the real service, which switches to the IO dispatcher first.
            currentCoroutineContext().ensureActive()
            deletedPaths += pendingUpload.path
            existingPaths -= pendingUpload.path
        }
    }
}
