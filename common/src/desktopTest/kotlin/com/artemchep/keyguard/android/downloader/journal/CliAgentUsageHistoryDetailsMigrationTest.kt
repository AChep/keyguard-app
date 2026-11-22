package com.artemchep.keyguard.android.downloader.journal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.keyguard.sync.assertMigratesToSchema
import com.artemchep.keyguard.provider.keyguard.sync.createWithEarlierTable
import com.artemchep.keyguard.provider.keyguard.sync.openDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The migration that gives the request log the id of the client and its details. */
class CliAgentUsageHistoryDetailsMigrationTest {
    @Test
    fun `migration 33 gives the request log the table the schema declares`() =
        assertMigratesToSchema(table = "cliAgentUsageHistory", version = 33, ::createVersion33)

    @Test
    fun `migration 33 keeps every row, with no client id or details`() {
        openDriver().use { driver ->
            createVersion33(driver)
            driver.execute(
                identifier = null,
                sql = "INSERT INTO cliAgentUsageHistory" +
                    "(sessionId, client, request, response, command, path, createdAt) " +
                    "VALUES ('session', 0, 0, 0, 'npm start', '/project', 1)",
                parameters = 0,
            )
            Database.Schema.migrate(driver, 33, 34)
            val database = createUploadTestDatabase(driver)

            val row = database.cliAgentUsageHistoryQueries
                .getRecentByClient(CliAgentUsageHistoryClient.CLI, 100)
                .executeAsList()
                .single()
            assertEquals("npm start", row.command)
            assertEquals("/project", row.path)
            assertNull(row.clientId)
            assertNull(row.details)
        }
    }

    /** The schema of version 33: the current one, with the request log that had no client id nor details yet. */
    private fun createVersion33(driver: JdbcSqliteDriver) = createWithEarlierTable(
        driver = driver,
        table = "cliAgentUsageHistory",
        """
            CREATE TABLE cliAgentUsageHistory (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              cipherId TEXT,
              sessionId TEXT NOT NULL,
              client INTEGER NOT NULL,
              caller TEXT,
              clientName TEXT,
              request INTEGER NOT NULL,
              response INTEGER NOT NULL,
              errorCode TEXT,
              command TEXT,
              path TEXT,
              createdAt INTEGER NOT NULL,
              eventId TEXT,
              FOREIGN KEY (cipherId) REFERENCES cipher(cipherId) ON DELETE CASCADE
            )
        """.trimIndent(),
        "CREATE INDEX idx_cliAgentUsageHistory_client_createdAt ON cliAgentUsageHistory(client, createdAt DESC)",
        "CREATE INDEX idx_cliAgentUsageHistory_cipherId_createdAt ON cliAgentUsageHistory(cipherId, createdAt DESC)",
        "CREATE UNIQUE INDEX idx_cliAgentUsageHistory_eventId ON cliAgentUsageHistory(eventId)",
    )
}
