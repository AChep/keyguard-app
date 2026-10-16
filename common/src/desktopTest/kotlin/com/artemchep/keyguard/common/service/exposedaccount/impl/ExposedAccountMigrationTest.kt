package com.artemchep.keyguard.common.service.exposedaccount.impl

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.test.withTempDirectory
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The migration that stopped mirroring account emails and server hosts.
 */
@Suppress("FunctionNaming")
class ExposedAccountMigrationTest {
    @Test
    fun `migration keeps names and entry ids, and drops emails and hosts`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.createVersion6Schema()

        var callbackColumns: List<String>? = null
        DatabaseExposed.Schema.migrate(
            driver = driver,
            oldVersion = 6,
            newVersion = DatabaseExposed.Schema.version,
            // The exposed database manager vacuums after this callback. It has
            // to fire once the email and host columns are already gone.
            AfterVersion(6) {
                callbackColumns = it.accountColumns()
            },
        )

        assertEquals(listOf("accountId", "name"), callbackColumns)
        assertEquals(listOf("accountId", "name"), driver.accountColumns())
        assertEquals(
            listOf("a:Personal", "b:"),
            driver.strings("SELECT accountId || ':' || name FROM account ORDER BY accountId"),
        )
        assertEquals(
            listOf("a:entry-a", "b:entry-b"),
            driver.strings(
                "SELECT accountId || ':' || entryId FROM credentialExchangeExportEntry ORDER BY accountId",
            ),
        )
    }

    @Test
    fun `an encrypted database still opens after the post-migration vacuum`() {
        withTempDirectory("keyguard-exposed-migration-test") { dir ->
            val file = dir.toFile().resolve("exposed.db")
            val open = {
                JdbcSqliteDriver(
                    url = "jdbc:sqlite:file:${file.absolutePath}",
                    properties = SQLiteMCSqlCipherConfig.getDefault()
                        .withRawUnsaltedKey(ByteArray(32) { it.toByte() })
                        .build()
                        .toProperties(),
                )
            }

            open().use { driver ->
                driver.createVersion6Schema()
                DatabaseExposed.Schema.migrate(
                    driver = driver,
                    oldVersion = 6,
                    newVersion = DatabaseExposed.Schema.version,
                )
                driver.execute(
                    identifier = null,
                    sql = "VACUUM",
                    parameters = 0,
                )
            }

            open().use { driver ->
                assertEquals(
                    listOf("a:Personal", "b:"),
                    driver.strings("SELECT accountId || ':' || name FROM account ORDER BY accountId"),
                )
            }
        }
    }
}

/**
 * The two tables the migration touches, as version 6 of the schema had them.
 */
private fun SqlDriver.createVersion6Schema() {
    sql(
        """
        CREATE TABLE account (
          accountId TEXT NOT NULL PRIMARY KEY,
          name TEXT NOT NULL,
          email TEXT NOT NULL,
          host TEXT NOT NULL
        )
        """.trimIndent(),
    )
    sql(
        """
        CREATE TABLE credentialExchangeExportEntry (
          accountId TEXT NOT NULL PRIMARY KEY,
          entryId TEXT NOT NULL
        )
        """.trimIndent(),
    )
    sql("INSERT INTO account VALUES ('a', 'Personal', 'alice@example.com', 'vault.example.com')")
    sql("INSERT INTO account VALUES ('b', '', 'bob@example.com', 'Bob.kdbx')")
    sql("INSERT INTO credentialExchangeExportEntry VALUES ('a', 'entry-a')")
    sql("INSERT INTO credentialExchangeExportEntry VALUES ('b', 'entry-b')")
}

private fun SqlDriver.accountColumns() = strings("SELECT name FROM pragma_table_info('account')")

private fun SqlDriver.sql(sql: String) = execute(null, sql, 0).value

private fun SqlDriver.strings(sql: String): List<String> = executeQuery(
    null,
    sql,
    { cursor ->
        QueryResult.Value(
            buildList {
                while (cursor.next().value) add(requireNotNull(cursor.getString(0)))
            },
        )
    },
    0,
).value
