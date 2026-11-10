package com.artemchep.keyguard.provider.keyguard.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UrlBlockSyncIdMigrationTest {
    @Test
    fun `migration 29 gives the blocked URIs the table the schema declares`() {
        val created = openDriver().use { driver ->
            Database.Schema.create(driver)
            describeTable(driver, "urlBlock")
        }
        val migrated = openDriver().use { driver ->
            createVersion29(driver)
            Database.Schema.migrate(driver, 29, 30)
            describeTable(driver, "urlBlock")
        }
        assertEquals(created, migrated)
    }

    @Test
    fun `migration 29 gives every blocked URI its own sync id and keeps its data`() {
        openDriver().use { driver ->
            createVersion29(driver)
            insertUrlBlock(driver, id = 1, name = "A", uri = "a.example", mode = "domain", exposed = null)
            insertUrlBlock(driver, id = 2, name = "B", uri = "^https://b\\.example/.*", mode = "regex", exposed = 0)
            insertUrlBlock(driver, id = 3, name = "C", uri = "c.example", mode = "host", exposed = 1, enabled = 0)
            Database.Schema.migrate(driver, 29, 30)
            val database = createUploadTestDatabase(driver)

            val rows = database.urlBlockQueries.get(100).executeAsList().sortedBy { it.id }
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
            assertEquals(listOf("A", "B", "C"), rows.map { it.name })
            assertEquals(listOf("a.example", "^https://b\\.example/.*", "c.example"), rows.map { it.uri })
            assertEquals(listOf("domain", "regex", "host"), rows.map { it.mode })
            assertEquals(listOf(null, 0L, 1L), rows.map { it.exposed })
            assertEquals(listOf(1L, 1L, 0L), rows.map { it.enabled })
            assertEquals(listOf("A description", "B description", "C description"), rows.map { it.description })
            assertEquals(3, rows.map { it.syncId }.toSet().size)
            assertTrue(rows.all { UUID_REGEX.matches(it.syncId) }, rows.map { it.syncId }.toString())

            // Two rules never share an id.
            val taken = rows.first().syncId
            assertFailsWith<SQLException> {
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO urlBlock(name, description, uri, mode, createdAt, enabled, syncId) " +
                        "VALUES ('D', '', 'd.example', 'domain', 4, 1, '$taken')",
                    parameters = 0,
                )
            }
        }
    }

    /** The schema of version 29: the current one, with the blocked URIs that had no sync ids yet. */
    private fun createVersion29(driver: JdbcSqliteDriver) {
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE urlBlock", 0)
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE urlBlock (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  description TEXT NOT NULL,
                  uri TEXT NOT NULL,
                  mode TEXT NOT NULL,
                  createdAt INTEGER NOT NULL,
                  enabled INTEGER NOT NULL,
                  exposed INTEGER
                )
            """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX idx_urlBlock_createdAt ON urlBlock(createdAt DESC)",
            parameters = 0,
        )
    }
}

@Suppress("LongParameterList")
private fun insertUrlBlock(
    driver: JdbcSqliteDriver,
    id: Long,
    name: String,
    uri: String,
    mode: String,
    exposed: Long?,
    enabled: Long = 1,
) {
    driver.execute(
        identifier = null,
        sql = "INSERT INTO urlBlock(id, name, description, uri, mode, createdAt, enabled, exposed) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        parameters = 8,
    ) {
        bindLong(0, id)
        bindString(1, name)
        bindString(2, "$name description")
        bindString(3, uri)
        bindString(4, mode)
        bindLong(5, id)
        bindLong(6, enabled)
        bindLong(7, exposed)
    }
}
