package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun `isEmpty reflects whether anything is staged`() {
        val db = createTestDatabase()
        insertAccount(db)
        val buffer = KeePassWriteBackBuffer(db)

        assertEquals(true, buffer.isEmpty)
        buffer.stageFolderUpsert(testBitwardenFolder(folderId = "f1", name = "F"))
        assertEquals(false, buffer.isEmpty)
    }
}
