package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepositoryImpl
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.insertLocalCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The last use of the ciphers, from `cipherUsageHistory`, as the browser extension's `suggest` reads it. */
class GetCipherLastUsedImplTest {
    @Test
    fun `the last autofill or passkey use of each cipher asked for`() = runTest {
        withDatabase { db ->
            db.insertCiphers(listOf("a", "b", "c", "d"))
            db.used("a", CipherHistoryType.USED_AUTOFILL, at = 1)
            db.used("a", CipherHistoryType.USED_AUTOFILL, at = 3)
            // Opening an item is no use.
            db.used("a", CipherHistoryType.OPENED, at = 9)
            db.used("b", CipherHistoryType.USED_PASSKEY, at = 2)
            db.used("c", CipherHistoryType.OPENED, at = 5)
            // Not asked for.
            db.used("d", CipherHistoryType.USED_AUTOFILL, at = 4)
            val getCipherLastUsed = getCipherLastUsed(db)

            val lastUsed = getCipherLastUsed(setOf("a", "b", "c", "unknown")).bind()
            assertEquals(mapOf("a" to instant(3), "b" to instant(2)), lastUsed)
            assertEquals(emptyMap(), getCipherLastUsed(emptySet()).bind())
        }
    }

    @Test
    fun `reads more ciphers than SQLite binds in one query`() = runTest {
        withDatabase { db ->
            val cipherIds = (1..2000).map { "cipher-$it" }
            db.insertCiphers(cipherIds)
            db.used("cipher-1", CipherHistoryType.USED_AUTOFILL, at = 1)
            db.used("cipher-2000", CipherHistoryType.USED_PASSKEY, at = 2)

            val lastUsed = getCipherLastUsed(db)(cipherIds.toSet()).bind()
            assertEquals(mapOf("cipher-1" to instant(1), "cipher-2000" to instant(2)), lastUsed)
        }
    }

    private fun getCipherLastUsed(db: Database) = GetCipherLastUsedImpl(
        cipherHistoryOpenedRepository = CipherHistoryOpenedRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(db),
            dispatcher = Dispatchers.Unconfined,
        ),
    )

    private fun Database.insertCiphers(cipherIds: List<String>) = transaction {
        cipherIds.forEach { id -> insertLocalCipher(this@insertCiphers, testBitwardenCipher(cipherId = id)) }
    }

    private fun Database.used(cipherId: String, type: Long, at: Long) {
        cipherUsageHistoryQueries.insert(
            cipherId = cipherId,
            credentialId = null,
            type = type,
            createdAt = instant(at),
        )
    }

    private fun instant(seconds: Long) = Instant.fromEpochSeconds(seconds)

    private suspend fun withDatabase(block: suspend (Database) -> Unit) {
        val driver = JdbcSqliteDriver(
            url = JdbcSqliteDriver.IN_MEMORY,
            properties = Properties().apply {
                setProperty("foreign_keys", "true")
                // SQLite's traditional parameter limit, as on older Android versions.
                setProperty("limit_variable_number", "999")
            },
        )
        try {
            Database.Schema.create(driver)
            val db = createUploadTestDatabase(driver)
            db.insertUploadTestAccount()
            block(db)
        } finally {
            driver.close()
        }
    }
}
