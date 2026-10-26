package com.artemchep.keyguard.common.service.export.impl

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.common.exception.KeePassAttachmentTooLargeException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.service.export.ExportVaultData
import com.artemchep.keyguard.common.service.export.KdbxAttachmentLoader
import com.artemchep.keyguard.common.service.keepass.keePassCipherProviders
import com.artemchep.keyguard.common.service.staging.StagingSpoolBacking
import com.artemchep.keyguard.common.service.staging.StagingSpoolEvent
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.staging.StagingSpoolObserver
import com.artemchep.keyguard.common.service.staging.StagingSpoolOutcome
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.TestPrivateTemporaryStorage
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFolderTreeByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFolderTreeImpl
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

@Suppress("TooManyFunctions")
class KdbxExportServiceImplTest {
    private companion object {
        const val PASSWORD = "export-password"

        const val WORK_FOLDER_ID = "6b6f1f9c-8d2a-4a8e-9a43-3a3d0f0e1a01"
        const val SERVERS_FOLDER_ID = "6b6f1f9c-8d2a-4a8e-9a43-3a3d0f0e1a02"
        const val LOGIN_CIPHER_ID = "6b6f1f9c-8d2a-4a8e-9a43-3a3d0f0e1b01"
        const val NOTE_CIPHER_ID = "6b6f1f9c-8d2a-4a8e-9a43-3a3d0f0e1b02"
        const val KEEPASS_ENTRY_UUID = "6b6f1f9c-8d2a-4a8e-9a43-3a3d0f0e1c01"
        const val KEEPASS_GROUP_UUID = "6b6f1f9c-8d2a-4a8e-9a43-3a3d0f0e1c02"

        val REVISION_DATE = Instant.parse("2024-01-01T00:00:00Z")

        const val LARGE_ATTACHMENT_SIZE = 3 * 1024 * 1024
    }

    @Test
    fun `export writes items into nested groups readable by the KeePass codec`() = runTest {
        val workFolder = testBitwardenFolder(folderId = WORK_FOLDER_ID, name = "Work")
        val serversFolder = testBitwardenFolder(folderId = SERVERS_FOLDER_ID, name = "Work/Servers")
        val login = testBitwardenCipher(cipherId = LOGIN_CIPHER_ID, name = "Server").copy(
            folderId = SERVERS_FOLDER_ID,
            type = BitwardenCipher.Type.Login,
            secureNote = null,
            login = BitwardenCipher.Login(
                username = "alice",
                password = "current",
                uris = listOf(
                    BitwardenCipher.Login.Uri(uri = "https://example.com"),
                ),
            ),
            passwordHistory = listOf(
                BitwardenCipher.Login.PasswordHistory(
                    password = "newer",
                    lastUsedDate = Instant.parse("2023-06-01T00:00:00Z"),
                ),
                BitwardenCipher.Login.PasswordHistory(
                    password = "older",
                    lastUsedDate = Instant.parse("2023-01-01T00:00:00Z"),
                ),
            ),
        )
        val note = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note")

        val database = export(
            ciphers = listOf(login, note),
            // Only the inner folder has items; its parent
            // must still become a group.
            folders = listOf(workFolder.toDomain(), serversFolder.toDomain()),
        )

        val root = database.content.group
        assertEquals(listOf(NOTE_CIPHER_ID), root.entries.map { it.uuid.toString() })
        val workGroup = root.groups.single()
        assertEquals("Work", workGroup.name)
        assertEquals(WORK_FOLDER_ID, workGroup.uuid.toString())
        assertTrue(workGroup.entries.isEmpty())
        val serversGroup = workGroup.groups.single()
        assertEquals("Servers", serversGroup.name)
        val entry = serversGroup.entries.single()
        assertEquals(LOGIN_CIPHER_ID, entry.uuid.toString())
        assertIs<EntryValue.Encrypted>(entry.fields[BasicField.Password()])

        val decoded = createTestCipherCodec().decode(
            accountId = "account",
            folderId = SERVERS_FOLDER_ID,
            cipherId = LOGIN_CIPHER_ID,
            remote = entry,
            local = null,
            revisionDate = REVISION_DATE,
            binaries = database.binaries,
        )
        assertEquals(BitwardenCipher.Type.Login, decoded.type)
        assertEquals("Server", decoded.name)
        assertEquals("alice", decoded.login?.username)
        assertEquals("current", decoded.login?.password)
        assertEquals(listOf("https://example.com"), decoded.login?.uris?.map { it.uri })
        assertEquals(listOf("newer", "older"), decoded.passwordHistory.map { it.password })
    }

    @Test
    fun `export keeps the UUIDs of KeePass entries and groups`() = runTest {
        val folder = testBitwardenFolder(folderId = WORK_FOLDER_ID, name = "Work").copy(
            service = keePassService(uuid = KEEPASS_GROUP_UUID),
        )
        val note = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note").copy(
            folderId = WORK_FOLDER_ID,
            service = keePassService(uuid = KEEPASS_ENTRY_UUID),
        )

        val database = export(
            ciphers = listOf(note),
            folders = listOf(folder.toDomain()),
        )

        val group = database.content.group.groups.single()
        assertEquals(KEEPASS_GROUP_UUID, group.uuid.toString())
        assertEquals(KEEPASS_ENTRY_UUID, group.entries.single().uuid.toString())
    }

    @Test
    fun `export does not reuse the UUID of an item from two accounts`() = runTest {
        // The same KeePass database, added as two accounts.
        val ciphers = listOf(
            testBitwardenCipher(cipherId = NOTE_CIPHER_ID, accountId = "first"),
            testBitwardenCipher(cipherId = LOGIN_CIPHER_ID, accountId = "second"),
        ).map { cipher ->
            cipher.copy(service = keePassService(uuid = KEEPASS_ENTRY_UUID))
        }

        val database = export(
            ciphers = ciphers,
        )

        assertEquals(
            listOf(KEEPASS_ENTRY_UUID, LOGIN_CIPHER_ID),
            database.content.group.entries.map { it.uuid.toString() },
        )
    }

    @Test
    fun `export embeds attachments`() = runTest {
        val note = noteWithAttachment(size = 5L)

        val database = export(
            ciphers = listOf(note),
            loadAttachment = { _, attachment, sink ->
                assertEquals("attachment", attachment.id)
                sink.write("hello".encodeToByteArray())
            },
        )

        val reference = database.content.group.entries.single().binaries.single()
        assertEquals("note.txt", reference.name)
        val binary = database.binaries.getValue(reference.hash)
        assertEquals("hello", binary.getContent().decodeToString())
    }

    @Test
    fun `export keeps large attachments in the staging spool and releases it`() = runTest {
        val note = noteWithAttachment(size = LARGE_ATTACHMENT_SIZE.toLong())
        // Larger than the part of the spool that is kept in memory.
        val content = Random(seed = 42).nextBytes(LARGE_ATTACHMENT_SIZE)
        val storages = mutableListOf<TestPrivateTemporaryStorage>()
        val events = mutableListOf<StagingSpoolEvent>()
        val stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
            scratchStorageFactory = {
                TestPrivateTemporaryStorage().also(storages::add)
            },
            observer = StagingSpoolObserver(events::add),
        )

        val database = export(
            ciphers = listOf(note),
            stagingSpoolFactory = stagingSpoolFactory,
            loadAttachment = { _, _, sink ->
                sink.write(content)
            },
        )

        val reference = database.content.group.entries.single().binaries.single()
        assertContentEquals(content, database.binaries.getValue(reference.hash).getContent())
        val event = events.single()
        assertEquals(StagingSpoolBacking.Spill, event.backing)
        assertEquals(StagingSpoolOutcome.Sealed, event.outcome)
        val storage = storages.single()
        assertEquals(1, storage.closeCount)
        // The spilled copy of the attachment must be encrypted.
        val stored = storage.storedBytes()
        val sample = content.copyOfRange(0, 32)
        val containsPlaintext = (0..stored.size - sample.size).any { offset ->
            stored.copyOfRange(offset, offset + sample.size).contentEquals(sample)
        }
        assertFalse(containsPlaintext)
    }

    @Test
    fun `export stages one attachment at a time`() = runTest {
        val ids = listOf("aaaaa", "bbbbb", "ccccc")
        val note = noteWithAttachment(size = LARGE_ATTACHMENT_SIZE.toLong()).let { cipher ->
            cipher.copy(
                attachments = ids.map { id ->
                    BitwardenCipher.Attachment.Remote(
                        id = id,
                        url = "https://example.com/$id",
                        fileName = "$id.txt",
                        size = LARGE_ATTACHMENT_SIZE.toLong(),
                    )
                },
            )
        }
        // Larger than the part of the spool that is kept in memory.
        val contents = ids.associateWith { id ->
            Random(seed = id.hashCode()).nextBytes(LARGE_ATTACHMENT_SIZE)
        }
        val storages = mutableListOf<TestPrivateTemporaryStorage>()
        val stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
            scratchStorageFactory = {
                // The previous attachment is already
                // written and its storage is released.
                assertTrue(storages.all { it.closeCount == 1 })
                TestPrivateTemporaryStorage().also(storages::add)
            },
        )

        val database = export(
            ciphers = listOf(note),
            stagingSpoolFactory = stagingSpoolFactory,
            loadAttachment = { _, attachment, sink ->
                sink.write(contents.getValue(attachment.id))
            },
        )

        assertEquals(ids.size, storages.size)
        assertTrue(storages.all { it.closeCount == 1 })
        val references = database.content.group.entries.single().binaries
        ids.zip(references).forEach { (id, reference) ->
            assertContentEquals(
                contents.getValue(id),
                database.binaries.getValue(reference.hash).getContent(),
            )
        }
    }

    @Test
    fun `export stores identical attachments once`() = runTest {
        val ids = listOf("aaaaa", "bbbbb", "ccccc")
        val note = noteWithAttachment(size = 5L).let { cipher ->
            cipher.copy(
                attachments = ids.map { id ->
                    BitwardenCipher.Attachment.Remote(
                        id = id,
                        url = "https://example.com/$id",
                        fileName = "$id.txt",
                        size = 5L,
                    )
                },
            )
        }
        val database = export(
            ciphers = listOf(note),
            loadAttachment = { _, _, sink ->
                sink.write("hello".encodeToByteArray())
            },
        )

        val references = database.content.group.entries.single().binaries
        assertEquals(ids.map { "$it.txt" }, references.map { it.name })
        assertEquals(1, references.map { it.hash }.distinct().size)
        assertEquals(1, database.binaries.size)
    }

    @Test
    fun `export without attachment loader skips attachments`() = runTest {
        val note = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note").copy(
            attachments = listOf(
                BitwardenCipher.Attachment.Local(
                    id = "attachment",
                    url = "file:///note.txt",
                    fileName = "note.txt",
                    size = 5L,
                ),
            ),
        )

        val database = export(
            ciphers = listOf(note),
        )

        assertTrue(database.content.group.entries.single().binaries.isEmpty())
        assertTrue(database.binaries.isEmpty())
    }

    @Test
    fun `export gives repeated attachment names a number`() = runTest {
        val fileNames = listOf("scan.pdf", "scan.pdf", "notes", "notes")
        val note = noteWithAttachment(size = 5L).let { cipher ->
            cipher.copy(
                attachments = fileNames.mapIndexed { index, fileName ->
                    BitwardenCipher.Attachment.Remote(
                        id = "attachment$index",
                        url = "https://example.com/$index",
                        fileName = fileName,
                        size = 5L,
                    )
                },
            )
        }

        val database = export(
            ciphers = listOf(note),
            loadAttachment = { _, attachment, sink ->
                sink.write(attachment.id.encodeToByteArray())
            },
        )

        val references = database.content.group.entries.single().binaries
        assertEquals(
            listOf("scan.pdf", "scan (1).pdf", "notes", "notes (1)"),
            references.map { it.name },
        )
        val contents = references.map { reference ->
            database.binaries.getValue(reference.hash).getContent().decodeToString()
        }
        assertEquals(fileNames.indices.map { "attachment$it" }, contents)
    }

    @Test
    fun `export puts trashed items into the recycle bin`() = runTest {
        val workFolder = testBitwardenFolder(folderId = WORK_FOLDER_ID, name = "Work")
        val note = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note")
        val trashed = testBitwardenCipher(cipherId = LOGIN_CIPHER_ID, name = "Trashed").copy(
            folderId = WORK_FOLDER_ID,
            deletedDate = REVISION_DATE,
        )

        val database = export(
            ciphers = listOf(note, trashed),
            folders = listOf(workFolder.toDomain()),
        )

        val root = database.content.group
        assertEquals(listOf(NOTE_CIPHER_ID), root.entries.map { it.uuid.toString() })
        // The folder has only trashed items, so it
        // does not become a group.
        val recycleBin = root.groups.single()
        assertTrue(database.content.meta.recycleBinEnabled)
        assertEquals(database.content.meta.recycleBinUuid, recycleBin.uuid)
        assertEquals(listOf(LOGIN_CIPHER_ID), recycleBin.entries.map { it.uuid.toString() })
    }

    @Test
    fun `export without trashed items has no recycle bin`() = runTest {
        val note = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note")

        val database = export(
            ciphers = listOf(note),
        )

        assertTrue(database.content.group.groups.isEmpty())
        assertEquals(null, database.content.meta.recycleBinUuid)
    }

    @Test
    fun `export fails before any download when an attachment is too large`() = runTest {
        val small = noteWithAttachment(size = 5L)
        val large = noteWithAttachment(size = KEEPASS_FILE_UPLOAD_MAX_BYTES + 1L).copy(
            cipherId = LOGIN_CIPHER_ID,
        )
        var downloads = 0

        assertFailsWith<KeePassAttachmentTooLargeException> {
            exportToBytes(
                ciphers = listOf(small, large),
                loadAttachment = { _, _, _ -> downloads++ },
            )
        }
        assertEquals(0, downloads)
    }

    @Test
    fun `export keeps attachments out of the password history`() = runTest {
        val login = noteWithAttachment(size = 5L).copy(
            type = BitwardenCipher.Type.Login,
            secureNote = null,
            login = BitwardenCipher.Login(
                username = "alice",
                password = "current",
                uris = emptyList(),
            ),
            fields = listOf(
                BitwardenCipher.Field(
                    type = BitwardenCipher.Field.Type.Hidden,
                    name = "PIN",
                    value = "1234",
                ),
            ),
            passwordHistory = listOf(
                BitwardenCipher.Login.PasswordHistory(
                    password = "older",
                    lastUsedDate = Instant.parse("2023-01-01T00:00:00Z"),
                ),
            ),
        )

        val database = export(
            ciphers = listOf(login),
            loadAttachment = { _, _, sink ->
                sink.write("hello".encodeToByteArray())
            },
        )

        val entry = database.content.group.entries.single()
        assertEquals(1, entry.binaries.size)
        assertTrue("PIN" in entry.fields)
        val snapshot = entry.history.single()
        assertTrue(snapshot.binaries.isEmpty())
        assertTrue(snapshot.fields.keys.all { it in BasicField.keys })
        assertEquals("older", snapshot.fields.password?.content)
        assertEquals("alice", snapshot.fields.userName?.content)
    }

    @Test
    fun `export can not be opened with a wrong password`() = runTest {
        val note = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note")

        val bytes = exportToBytes(
            ciphers = listOf(note),
        )

        assertFailsWith<CryptoError.InvalidKey> {
            decode(bytes, password = "wrong-password")
        }
        val database = decode(bytes, password = PASSWORD)
        assertEquals(listOf(NOTE_CIPHER_ID), database.content.group.entries.map { it.uuid.toString() })
    }

    private suspend fun export(
        ciphers: List<BitwardenCipher>,
        folders: List<DFolder> = emptyList(),
        stagingSpoolFactory: StagingSpoolFactory = createStagingSpoolFactory(),
        loadAttachment: KdbxAttachmentLoader? = null,
    ): KeePassDatabase {
        val bytes = exportToBytes(
            ciphers = ciphers,
            folders = folders,
            stagingSpoolFactory = stagingSpoolFactory,
            loadAttachment = loadAttachment,
        )
        return decode(bytes, password = PASSWORD)
    }

    private suspend fun exportToBytes(
        ciphers: List<BitwardenCipher>,
        folders: List<DFolder> = emptyList(),
        stagingSpoolFactory: StagingSpoolFactory = createStagingSpoolFactory(),
        loadAttachment: KdbxAttachmentLoader? = null,
    ): ByteArray {
        val service = KdbxExportServiceImpl(
            cipherRepository = FakeCipherRepository(ciphers),
            getFolderTreeById = GetFolderTreeByIdImpl(
                getFolders = object : GetFolders {
                    override fun invoke() = flowOf(folders)
                },
                getFolderTree = GetFolderTreeImpl(),
            ),
            cipherCodec = createTestCipherCodec(),
            stagingSpoolFactory = stagingSpoolFactory,
        )
        val data = ExportVaultData(
            ciphers = ciphers.map { it.toDomain(UploadTestPasswordStrength) },
            folders = folders,
            collections = emptyList(),
            organizations = emptyList(),
        )
        val sink = Buffer()
        service.export(
            sink = sink,
            password = PASSWORD,
            data = data,
            loadAttachment = loadAttachment,
        )
        return sink.readByteArray()
    }

    private fun decode(
        bytes: ByteArray,
        password: String,
    ): KeePassDatabase = KeePassDatabase.decode(
        source = Buffer().apply { write(bytes) },
        credentials = Credentials.from(EncryptedValue.fromString(password)),
        cipherProviders = keePassCipherProviders,
    )

    private fun createStagingSpoolFactory(): StagingSpoolFactory =
        DefaultStagingSpoolFactory.forTesting(
            scratchStorageFactory = { TestPrivateTemporaryStorage() },
        )

    private fun keePassService(
        uuid: String,
    ) = BitwardenService(
        remote = BitwardenService.Remote(
            id = uuid,
            revisionDate = REVISION_DATE,
            deletedDate = null,
        ),
        version = BitwardenService.VERSION,
    )

    private fun noteWithAttachment(
        size: Long,
    ) = testBitwardenCipher(cipherId = NOTE_CIPHER_ID, name = "Note").copy(
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = "remote-note",
                revisionDate = REVISION_DATE,
                deletedDate = null,
            ),
            version = BitwardenService.VERSION,
        ),
        attachments = listOf(
            BitwardenCipher.Attachment.Remote(
                id = "attachment",
                url = "https://example.com/attachment",
                fileName = "note.txt",
                size = size,
            ),
        ),
    )

    private class FakeCipherRepository(
        private val ciphers: List<BitwardenCipher>,
    ) : BitwardenCipherRepository {
        override fun get(): Flow<List<BitwardenCipher>> = flowOf(ciphers)

        override fun getById(id: String): IO<BitwardenCipher?> = ioEffect {
            ciphers.firstOrNull { it.cipherId == id }
        }

        override fun put(model: BitwardenCipher): IO<Unit> = error("Not used by this test")
    }
}
