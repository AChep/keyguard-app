package com.artemchep.keyguard.provider.keyguard.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlBlockScopeMigrationTest {
    @Test
    fun `migration 32 gives the blocked URIs the table the schema declares`() =
        assertMigratesToSchema(table = "urlBlock", version = 32, ::createVersion32)

    @Test
    fun `migration 32 keeps every blocked URI as a rule that blocks everything`() {
        openDriver().use { driver ->
            createVersion32(driver)
            insertUrlBlock(driver, id = 1, name = "A", syncId = "a")
            insertUrlBlock(driver, id = 2, name = "B", syncId = "b")
            Database.Schema.migrate(driver, 32, 33)
            val database = createUploadTestDatabase(driver)

            val rows = database.urlBlockQueries.get(100).executeAsList().sortedBy { it.id }
            assertEquals(listOf("A", "B"), rows.map { it.name })
            assertEquals(listOf("a", "b"), rows.map { it.syncId })
            assertEquals(listOf(UrlBlockScope.ALL.key, UrlBlockScope.ALL.key), rows.map { it.scope })
        }
    }

    /** The schema of version 32: the current one, with the blocked URIs that had no scope yet. */
    private fun createVersion32(driver: JdbcSqliteDriver) = createWithEarlierTable(
        driver = driver,
        table = "urlBlock",
        """
            CREATE TABLE urlBlock (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              description TEXT NOT NULL,
              uri TEXT NOT NULL,
              mode TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              enabled INTEGER NOT NULL,
              exposed INTEGER,
              syncId TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent(),
        "CREATE INDEX idx_urlBlock_createdAt ON urlBlock(createdAt DESC)",
        "CREATE UNIQUE INDEX idx_urlBlock_syncId ON urlBlock(syncId)",
    )
}

private fun insertUrlBlock(
    driver: JdbcSqliteDriver,
    id: Long,
    name: String,
    syncId: String,
) {
    driver.execute(
        identifier = null,
        sql = "INSERT INTO urlBlock(id, name, description, uri, mode, createdAt, enabled, exposed, syncId) " +
            "VALUES (?, ?, '', 'example.com', 'domain', ?, 1, 1, ?)",
        parameters = 4,
    ) {
        bindLong(0, id)
        bindString(1, name)
        bindLong(2, id)
        bindString(3, syncId)
    }
}
