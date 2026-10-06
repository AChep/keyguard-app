package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.insertLocalCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UploadTestDatabaseTest {
    @Test
    fun `cipher rows require an existing account and are deleted with it`() {
        val db = createUploadTestDatabase()
        val cipher = testBitwardenCipher(cipherId = "cipher-1")

        val failure = assertFailsWith<SQLException> {
            insertLocalCipher(db, cipher)
        }
        assertTrue(failure.message.orEmpty().contains("FOREIGN KEY"))

        db.insertUploadTestAccount(cipher.accountId)
        insertLocalCipher(db, cipher)
        assertEquals(cipher.cipherId, db.cipherQueries.get().executeAsOne().cipherId)

        db.accountQueries.deleteByAccountId(cipher.accountId)
        assertTrue(db.cipherQueries.get().executeAsList().isEmpty())
    }
}
