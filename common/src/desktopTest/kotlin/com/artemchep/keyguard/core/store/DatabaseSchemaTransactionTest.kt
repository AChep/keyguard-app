package com.artemchep.keyguard.core.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DatabaseSchemaTransactionTest {
    @Test
    fun `creation and version survive reopening`() = withDatabase { open ->
        open().use { it.initializeSchema(schema(1)) }
        open().use { driver ->
            assertEquals(1L, driver.version())
            assertEquals(listOf("original"), driver.strings("SELECT value FROM entry"))
        }
    }

    @Test
    fun `failed creation rolls back and can be retried`() = withDatabase { open ->
        open().use { driver ->
            assertFailsWith<MigrationFailure> {
                driver.initializeSchema(schema(1, create = {
                    it.sql("CREATE TABLE entry (value TEXT NOT NULL)")
                    throw MigrationFailure()
                }))
            }
            assertNull(driver.currentTransaction())
        }
        open().use { driver ->
            assertEquals(0L, driver.version())
            assertEquals(emptyList(), driver.tables())
            driver.initializeSchema(schema(1))
        }
        open().use { assertEquals(1L, it.version()) }
    }

    @Test
    fun `SQL failure rolls back earlier schema and data changes`() = withDatabase { open ->
        open().use { it.initializeSchema(schema(1)) }
        open().use { driver ->
            assertFailsWith<java.sql.SQLException> {
                driver.initializeSchema(schema(2, migrate = {
                    it.sql("ALTER TABLE entry ADD COLUMN extra TEXT")
                    it.sql("UPDATE entry SET value = 'changed'")
                    it.sql("INSERT INTO missing_table VALUES (1)")
                }))
            }
            assertNull(driver.currentTransaction())
        }
        assertOriginalAndRetry(open)
    }

    @Test
    fun `callback failure rolls back the entire upgrade`() = withDatabase { open ->
        open().use { it.initializeSchema(schema(1)) }
        open().use { driver ->
            assertFailsWith<MigrationFailure> {
                driver.initializeSchema(
                    schema(2, migrate = ::upgrade),
                    AfterVersion(1) {
                        it.sql("UPDATE entry SET value = 'callback'")
                        throw MigrationFailure()
                    },
                )
            }
        }
        assertOriginalAndRetry(open)
    }

    @Test
    fun `generated migration restores dropped tables when a callback fails`() = withDatabase { open ->
        open().use { driver ->
            driver.initializeSchema(schema(4, create = {
                it.sql("CREATE TABLE gpgAgentPublicKey (value TEXT)")
                it.sql("INSERT INTO gpgAgentPublicKey VALUES ('retained')")
                it.sql("CREATE TABLE sshAgentPublicKey (value TEXT)")
                it.sql("INSERT INTO sshAgentPublicKey VALUES ('retained')")
            }))
        }
        open().use { driver ->
            assertFailsWith<MigrationFailure> {
                driver.initializeSchema(DatabaseExposed.Schema, AfterVersion(4) {
                    throw MigrationFailure()
                })
            }
        }
        open().use { driver ->
            assertEquals(4L, driver.version())
            assertEquals(listOf("gpgAgentPublicKey", "sshAgentPublicKey"), driver.tables().sorted())
            assertEquals(listOf("retained"), driver.strings("SELECT value FROM gpgAgentPublicKey"))
            assertEquals(listOf("retained"), driver.strings("SELECT value FROM sshAgentPublicKey"))
            driver.initializeSchema(DatabaseExposed.Schema)
        }
        open().use { driver ->
            assertEquals(DatabaseExposed.Schema.version, driver.version())
            assertEquals(
                listOf(
                    "gpgAgentKeyInfo",
                    "gpgCertificationAuthority",
                    "gpgPublicKey",
                    "pendingUsageHistory",
                    "sshAgentPublicKey",
                ),
                driver.tables().sorted(),
            )
        }
    }

    @Test
    fun `failure after version update rolls back the version too`() = withDatabase { open ->
        open().use { it.initializeSchema(schema(1)) }
        open().use { driver ->
            val failingDriver = object : SqlDriver by driver {
                override fun execute(
                    identifier: Int?,
                    sql: String,
                    parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<Long> {
                    val result = driver.execute(identifier, sql, parameters, binders)
                    if (sql.startsWith("PRAGMA user_version =")) throw MigrationFailure()
                    return result
                }
            }
            assertFailsWith<MigrationFailure> {
                failingDriver.initializeSchema(schema(2, migrate = ::upgrade))
            }
        }
        assertOriginalAndRetry(open)
    }

    @Test
    fun `version read failure does not create a schema`() = withDatabase { open ->
        open().use { driver ->
            val failingDriver = object : SqlDriver by driver {
                override fun <R> executeQuery(
                    identifier: Int?,
                    sql: String,
                    mapper: (SqlCursor) -> QueryResult<R>,
                    parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<R> = throw MigrationFailure()
            }
            assertFailsWith<MigrationFailure> { failingDriver.initializeSchema(schema(1)) }
            assertNull(driver.currentTransaction())
        }
        open().use {
            assertEquals(0L, it.version())
            assertEquals(emptyList(), it.tables())
        }
    }

    @Test
    fun `downgrade is rejected without changing the database`() = withDatabase { open ->
        open().use { it.initializeSchema(schema(2)) }
        open().use { driver ->
            val error = assertFailsWith<DatabaseSchemaDowngradeException> {
                driver.initializeSchema(schema(1))
            }
            assertEquals(2L, error.currentVersion)
            assertEquals(1L, error.targetVersion)
        }
        open().use {
            assertEquals(2L, it.version())
            assertEquals(listOf("original"), it.strings("SELECT value FROM entry"))
        }
    }

    @Test
    fun `current schema is not created or migrated again`() = withDatabase { open ->
        open().use { it.initializeSchema(schema(1)) }
        open().use {
            it.initializeSchema(schema(
                1,
                create = { throw MigrationFailure() },
                migrate = { throw MigrationFailure() },
            ))
            assertEquals(1L, it.version())
        }
    }
}

private fun assertOriginalAndRetry(open: () -> SqlDriver) {
    open().use { driver ->
        assertEquals(1L, driver.version())
        assertEquals(listOf("original"), driver.strings("SELECT value FROM entry"))
        assertEquals(listOf("value"), driver.strings("SELECT name FROM pragma_table_info('entry')"))
        driver.initializeSchema(
            schema(2, migrate = ::upgrade),
            AfterVersion(1) { it.sql("UPDATE entry SET value = 'callback'") },
        )
    }
    open().use { driver ->
        assertEquals(2L, driver.version())
        assertEquals(listOf("callback"), driver.strings("SELECT value FROM entry"))
        assertEquals(listOf("value", "extra"), driver.strings("SELECT name FROM pragma_table_info('entry')"))
    }
}

private fun upgrade(driver: SqlDriver) {
    driver.sql("ALTER TABLE entry ADD COLUMN extra TEXT")
    driver.sql("UPDATE entry SET value = 'changed'")
}

private fun schema(
    version: Long,
    create: (SqlDriver) -> Unit = {
        it.sql("CREATE TABLE entry (value TEXT NOT NULL)")
        it.sql("INSERT INTO entry VALUES ('original')")
    },
    migrate: (SqlDriver) -> Unit = {},
): SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version = version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        create.invoke(driver)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        migrate.invoke(driver)
        callbacks.filter { it.afterVersion in oldVersion until newVersion }
            .forEach { it.block(driver) }
        return QueryResult.Unit
    }
}

private fun withDatabase(block: (() -> SqlDriver) -> Unit) {
    val directory = Files.createTempDirectory("keyguard-migration-test").toFile()
    try {
        val file = directory.resolve("vault.db")
        block {
            JdbcSqliteDriver(
                url = "jdbc:sqlite:file:${file.absolutePath}",
                properties = SQLiteMCSqlCipherConfig.getDefault()
                    .withRawUnsaltedKey(ByteArray(32) { it.toByte() })
                    .build()
                    .toProperties()
                    .apply { put("foreign_keys", "true") },
            )
        }
    } finally {
        directory.deleteRecursively()
    }
}

private fun SqlDriver.sql(sql: String) = execute(null, sql, 0).value

private fun SqlDriver.version() = strings("PRAGMA user_version").single().toLong()

private fun SqlDriver.tables() = strings("SELECT name FROM sqlite_master WHERE type = 'table'")

private fun SqlDriver.strings(sql: String): List<String> = executeQuery(
    null,
    sql,
    { cursor ->
        QueryResult.Value(buildList {
            while (cursor.next().value) add(requireNotNull(cursor.getString(0)))
        })
    },
    0,
).value

private class MigrationFailure : RuntimeException()
