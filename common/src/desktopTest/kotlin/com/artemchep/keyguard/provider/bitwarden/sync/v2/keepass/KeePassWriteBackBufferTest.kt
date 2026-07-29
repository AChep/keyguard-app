package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Suppress("FunctionNaming")
class KeePassWriteBackBufferTest {
    @Test
    fun `overlay read returns staged upsert before commit`() {
        val db = createTestDatabase()
        insertAccount(db)
        val buffer = KeePassWriteBackBuffer(db)

        buffer.stageCipherUpsert(testBitwardenCipher(cipherId = "c1", name = "Staged"))

        assertEquals("Staged", buffer.readCipher("c1")?.name)
        // Nothing is written to SQLite until commit.
        assertNull(db.cipherQueries.getByCipherId("c1").executeAsOneOrNull())
    }

    @Test
    fun `overlay read reflects a staged delete as absent`() {
        val db = createTestDatabase()
        insertAccount(db)
        insertLocalCipher(db, testBitwardenCipher(cipherId = "c1"))
        val buffer = KeePassWriteBackBuffer(db)

        buffer.stageCipherDelete("c1")

        assertNull(buffer.readCipher("c1"))
        // The committed row is still there until commit runs.
        assertNotNull(db.cipherQueries.getByCipherId("c1").executeAsOneOrNull())
    }

    @Test
    fun `commit applies staged upserts and deletes`() {
        val db = createTestDatabase()
        insertAccount(db)
        insertLocalCipher(db, testBitwardenCipher(cipherId = "to-delete"))
        val buffer = KeePassWriteBackBuffer(db)

        buffer.stageCipherUpsert(testBitwardenCipher(cipherId = "to-add", name = "Added"))
        buffer.stageCipherDelete("to-delete")
        buffer.commit(db)

        assertEquals(
            "Added",
            db.cipherQueries.getByCipherId("to-add").executeAsOneOrNull()?.data_?.name,
        )
        assertNull(db.cipherQueries.getByCipherId("to-delete").executeAsOneOrNull())
    }

    @Test
    fun `commit skips a row a user changed concurrently after staging`() {
        val db = createTestDatabase()
        insertAccount(db)
        val original = testBitwardenCipher(cipherId = "c1", name = "Original")
        insertLocalCipher(db, original)
        val buffer = KeePassWriteBackBuffer(db)

        // The sync stages an update (pre-image captured = "Original").
        buffer.stageCipherUpsert(original.copy(name = "SyncUpdate"))
        // The user edits the same row out of band before the buffer commits.
        insertLocalCipher(db, original.copy(name = "UserEdit"))

        buffer.commit(db)

        // The concurrent user edit must win; the stale sync write is dropped.
        assertEquals(
            "UserEdit",
            db.cipherQueries.getByCipherId("c1").executeAsOneOrNull()?.data_?.name,
        )
    }

    @Test
    fun `commit reports a staged attachment after its local reference is removed`() {
        val db = createTestDatabase()
        insertAccount(db)
        val pendingUpload = pendingUpload()
        val original = testBitwardenCipher(cipherId = "c1").copy(
            attachments = listOf(localAttachment(pendingUpload)),
        )
        insertLocalCipher(db, original)
        val buffer = KeePassWriteBackBuffer(db)

        buffer.stageCipherUpsert(
            original.copy(attachments = emptyList()),
        )

        assertEquals(
            listOf(pendingUpload),
            buffer.commit(db),
        )
    }

    @Test
    fun `commit keeps a staged attachment referenced by a concurrent edit`() {
        val db = createTestDatabase()
        insertAccount(db)
        val pendingUpload = pendingUpload()
        val original = testBitwardenCipher(cipherId = "c1").copy(
            attachments = listOf(localAttachment(pendingUpload)),
        )
        insertLocalCipher(db, original)
        val buffer = KeePassWriteBackBuffer(db)

        buffer.stageCipherUpsert(
            original.copy(attachments = emptyList()),
        )
        insertLocalCipher(
            db,
            original.copy(name = "Concurrent edit"),
        )

        assertEquals(emptyList(), buffer.commit(db))
        assertEquals(
            listOf(localAttachment(pendingUpload)),
            db.cipherQueries
                .getByCipherId("c1")
                .executeAsOne()
                .data_
                .attachments,
        )
    }

    @Test
    fun `isEmpty reflects whether anything is staged`() {
        val db = createTestDatabase()
        insertAccount(db)
        val buffer = KeePassWriteBackBuffer(db)

        assertEquals(true, buffer.isEmpty)
        buffer.stageFolderUpsert(testBitwardenFolder(folderId = "f1", name = "F"))
        assertEquals(false, buffer.isEmpty)
    }
}

private fun pendingUpload() = PendingUploadFile(
    path = "/private/pending/c1.a1.bin",
    plainSize = 10L,
    encryptedSize = 59L,
)

private fun localAttachment(
    pendingUpload: PendingUploadFile,
) = BitwardenCipher.Attachment.Local(
    id = "a1",
    url = "content://revoked/original",
    fileName = "attachment.txt",
    size = pendingUpload.plainSize,
    keyBase64 = "attachment-key",
    pendingUpload = pendingUpload,
)
