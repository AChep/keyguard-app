package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testCipher
import kotlinx.coroutines.test.runTest
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class MarkWatchtowerAlertsAsReadImplTest {
    @Test
    fun `marks selected alerts across batches without reading unrelated alerts`() = runTest {
        withDatabase { db, _ ->
            val selectedIds = selectedIds()
            db.insertAlerts(selectedIds + CipherId("unrelated"))
            val markAsRead = MarkWatchtowerAlertsAsReadImpl(UploadTestVaultDatabaseManager(db))

            markAsRead(selectedIds).bind()

            val alerts = db.watchtowerThreatQueries.getThreats().executeAsList()
            assertEquals(selectedIds.size + 1, alerts.size)
            assertEquals(
                selectedIds.map { it.id }.toSet(),
                alerts.filter { it.read }.map { it.cipherId }.toSet(),
            )
        }
    }

    @Test
    fun `empty selection leaves alerts unchanged`() = runTest {
        withDatabase { db, _ ->
            db.insertAlerts(setOf(CipherId("unread"), CipherId("already-read")))
            db.watchtowerThreatQueries.markAsRead("already-read")
            val before = db.watchtowerThreatQueries.getThreats().executeAsList()
            val markAsRead = MarkWatchtowerAlertsAsReadImpl(UploadTestVaultDatabaseManager(db))

            markAsRead(emptySet()).bind()

            assertEquals(before, db.watchtowerThreatQueries.getThreats().executeAsList())
        }
    }

    @Test
    fun `failure in a later batch rolls back all updates`() = runTest {
        withDatabase { db, driver ->
            val selectedIds = selectedIds()
            db.insertAlerts(selectedIds)
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TRIGGER fail_mark_as_read
                    BEFORE UPDATE OF read ON watchtowerThreat
                    WHEN NEW.cipherId = 'selected-2000'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced update failure');
                    END;
                """.trimIndent(),
                parameters = 0,
            )
            val markAsRead = MarkWatchtowerAlertsAsReadImpl(UploadTestVaultDatabaseManager(db))

            val failure = assertFailsWith<SQLException> {
                markAsRead(selectedIds).bind()
            }

            assertTrue(failure.message.orEmpty().contains("forced update failure"))
            val alerts = db.watchtowerThreatQueries.getThreats().executeAsList()
            assertEquals(selectedIds.size, alerts.size)
            assertTrue(alerts.none { it.read })
        }
    }

    private fun selectedIds(): Set<CipherId> = (1..2000)
        .mapTo(linkedSetOf()) { CipherId("selected-$it") }

    private fun Database.insertAlerts(cipherIds: Set<CipherId>) {
        transaction {
            cipherIds.forEach { id ->
                val cipher = testCipher(
                    localId = id.id,
                    remoteId = "remote-${id.id}",
                    localRevisionDate = REPORTED_AT,
                    remoteRevisionDate = REPORTED_AT,
                    attachments = emptyList(),
                )
                cipherQueries.insert(
                    cipherId = cipher.cipherId,
                    accountId = cipher.accountId,
                    folderId = cipher.folderId,
                    data = cipher,
                    updatedAt = REPORTED_AT,
                )
                watchtowerThreatQueries.upsert(
                    value = null,
                    threat = true,
                    cipherId = cipher.cipherId,
                    type = 1L,
                    reportedAt = REPORTED_AT,
                    version = "1",
                    cipherDataRevCounter = 0L,
                )
            }
        }
    }

    private suspend fun withDatabase(block: suspend (Database, JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(
            url = JdbcSqliteDriver.IN_MEMORY,
            properties = Properties().apply {
                // Exercise SQLite's real parameter limit with a small vault fixture.
                setProperty("limit_variable_number", "999")
            },
        )
        try {
            Database.Schema.create(driver)
            val db = createUploadTestDatabase(driver)
            block(db, driver)
        } finally {
            driver.close()
        }
    }

    private companion object {
        val REPORTED_AT = Instant.fromEpochMilliseconds(1_000L)
    }
}
