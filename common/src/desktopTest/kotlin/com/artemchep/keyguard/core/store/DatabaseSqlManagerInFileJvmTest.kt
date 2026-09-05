package com.artemchep.keyguard.core.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseSqlManagerInFileJvmTest {
    @Test
    fun `interrupted schema migration rolls back before retry`() {
        var attempts = 0
        val schema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 2L

            override fun create(driver: SqlDriver) = QueryResult.Value(Unit)

            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: AfterVersion,
            ): QueryResult.Value<Unit> {
                assertEquals(1L, oldVersion)
                assertEquals(2L, newVersion)
                driver.execute(null, "CREATE TABLE example (id INTEGER PRIMARY KEY)", 0, null)
                attempts += 1
                if (attempts == 1) {
                    error("simulated interruption")
                }
                return QueryResult.Value(Unit)
            }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        try {
            driver.execute(null, "PRAGMA user_version = 1", 0, null).value
            val firstAttempt = runCatching {
                migrateDatabaseSchema(driver, schema)
            }
            assertTrue(firstAttempt.isFailure)
            migrateDatabaseSchema(driver, schema)

            assertEquals(2, attempts)
            val count = driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM example",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0,
                binders = null,
            ).value
            assertEquals(0L, count)
        } finally {
            driver.close()
        }
    }
}
