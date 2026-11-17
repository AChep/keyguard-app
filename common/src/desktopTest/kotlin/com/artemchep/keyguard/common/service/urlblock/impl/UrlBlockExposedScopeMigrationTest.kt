package com.artemchep.keyguard.common.service.urlblock.impl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import com.artemchep.keyguard.provider.keyguard.sync.describeTable
import com.artemchep.keyguard.provider.keyguard.sync.openDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/** The migration that mirrors the scope of a blocked URI. */
class UrlBlockExposedScopeMigrationTest {
    @Test
    fun `migration 8 gives the blocked URIs the table the schema declares`() {
        val created = openDriver().use { driver ->
            DatabaseExposed.Schema.create(driver)
            describeTable(driver, "urlBlock")
        }
        val migrated = openDriver().use { driver ->
            createVersion8(driver)
            DatabaseExposed.Schema.migrate(driver, 8, 9)
            describeTable(driver, "urlBlock")
        }
        assertEquals(created, migrated)
    }

    @Test
    fun `migration 8 keeps every copy as a rule that blocks everything`() {
        openDriver().use { driver ->
            createVersion8(driver)
            driver.execute(
                identifier = null,
                sql = "INSERT INTO urlBlock(id, name, description, uri, mode, createdAt, enabled) " +
                    "VALUES (7, 'Bank', '', 'example.com', 'domain', 1, 1)",
                parameters = 0,
            )
            DatabaseExposed.Schema.migrate(driver, 8, 9)
            val database = DatabaseExposed(driver, urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter))

            val row = database.urlBlockQueries.get(100).executeAsList().single()
            assertEquals(7L, row.id)
            assertEquals("Bank", row.name)
            assertEquals(UrlBlockScope.ALL.key, row.scope)
        }
    }

    /** The schema of version 8: the current one, with the copies that had no scope yet. */
    private fun createVersion8(driver: JdbcSqliteDriver) {
        DatabaseExposed.Schema.create(driver)
        driver.execute(identifier = null, sql = "DROP TABLE urlBlock", parameters = 0)
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE urlBlock (
                  id INTEGER PRIMARY KEY,
                  name TEXT NOT NULL,
                  description TEXT NOT NULL,
                  uri TEXT NOT NULL,
                  mode TEXT NOT NULL,
                  createdAt INTEGER NOT NULL,
                  enabled INTEGER NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        )
    }
}
