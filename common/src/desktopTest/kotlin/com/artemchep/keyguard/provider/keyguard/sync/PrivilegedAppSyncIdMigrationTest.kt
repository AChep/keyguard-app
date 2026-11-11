package com.artemchep.keyguard.provider.keyguard.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrivilegedAppSyncIdMigrationTest {
    @Test
    fun `migration 30 gives the privileged apps the table the schema declares`() =
        assertMigratesToSchema(table = "privilegedApp", version = 30, ::createVersion30)

    @Test
    fun `migration 30 gives every privileged app its own sync id and keeps its data`() {
        openDriver().use { driver ->
            createVersion30(driver)
            insertPrivilegedApp(driver, id = 1, name = null, packageName = "com.example.a", fingerprint = "AA:AA")
            insertPrivilegedApp(driver, id = 2, name = "B", packageName = "com.example.b", fingerprint = "BB:BB")
            // The same app twice: the table never refused it.
            insertPrivilegedApp(driver, id = 3, name = null, packageName = "com.example.a", fingerprint = "AA:AA")
            Database.Schema.migrate(driver, 30, 31)
            val database = createUploadTestDatabase(driver)

            val rows = database.privilegedAppQueries.get(100).executeAsList().sortedBy { it.id }
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
            assertEquals(listOf(null, "B", null), rows.map { it.name })
            assertEquals(listOf("com.example.a", "com.example.b", "com.example.a"), rows.map { it.packageName })
            assertEquals(listOf("AA:AA", "BB:BB", "AA:AA"), rows.map { it.certFingerprintSha256 })
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.createdAt.toEpochMilliseconds() })
            assertOwnSyncIds(3, rows.map { it.syncId })

            // Two apps never share an id.
            val taken = rows.first().syncId
            assertFailsWith<SQLException> {
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO privilegedApp(packageName, certFingerprintSha256, createdAt, syncId) " +
                        "VALUES ('com.example.d', 'DD:DD', 4, '$taken')",
                    parameters = 0,
                )
            }
        }
    }

    /** The schema of version 30: the current one, with the privileged apps that had no sync ids yet. */
    private fun createVersion30(driver: JdbcSqliteDriver) = createWithEarlierTable(
        driver = driver,
        table = "privilegedApp",
        """
            CREATE TABLE privilegedApp (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT,
              packageName TEXT NOT NULL,
              certFingerprintSha256 TEXT NOT NULL,
              createdAt INTEGER NOT NULL
            )
        """.trimIndent(),
        "CREATE INDEX idx_privilegedApp_createdAt ON privilegedApp(createdAt DESC)",
    )
}

private fun insertPrivilegedApp(
    driver: JdbcSqliteDriver,
    id: Long,
    name: String?,
    packageName: String,
    fingerprint: String,
) {
    driver.execute(
        identifier = null,
        sql = "INSERT INTO privilegedApp(id, name, packageName, certFingerprintSha256, createdAt) " +
            "VALUES (?, ?, ?, ?, ?)",
        parameters = 5,
    ) {
        bindLong(0, id)
        bindString(1, name)
        bindString(2, packageName)
        bindString(3, fingerprint)
        bindLong(4, id)
    }
}
