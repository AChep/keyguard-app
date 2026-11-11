package com.artemchep.keyguard.provider.keyguard.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.data.Database
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TABLE_INFO_COLUMNS = 6
private const val FOREIGN_KEY_LIST_COLUMNS = 8
private const val INDEX_LIST_COLUMNS = 5

/** TEST ONLY: a random UUID, the form of a sync id. */
internal val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** TEST ONLY: an empty in-memory database that enforces foreign keys. */
internal fun openDriver() = JdbcSqliteDriver(
    url = JdbcSqliteDriver.IN_MEMORY,
    properties = Properties().apply {
        setProperty("foreign_keys", "true")
    },
)

/** TEST ONLY: the columns, foreign keys and indexes of the [table]. */
internal fun describeTable(driver: JdbcSqliteDriver, table: String): List<String> =
    pragma(driver, "PRAGMA table_info($table)", columns = TABLE_INFO_COLUMNS) +
        pragma(driver, "PRAGMA foreign_key_list($table)", columns = FOREIGN_KEY_LIST_COLUMNS) +
        pragma(driver, "PRAGMA index_list($table)", columns = INDEX_LIST_COLUMNS)
            // Skip the sequence number, which depends on the order of creation.
            .map { it.substringAfter('|') }
            .sorted()

/**
 * TEST ONLY: a database of the version before a migration changed the [table]: the current
 * schema, with the table and its indexes as the [statements] made them then.
 */
internal fun createWithEarlierTable(
    driver: JdbcSqliteDriver,
    table: String,
    vararg statements: String,
) {
    Database.Schema.create(driver)
    driver.execute(identifier = null, sql = "DROP TABLE $table", parameters = 0)
    statements.forEach { sql ->
        driver.execute(identifier = null, sql = sql, parameters = 0)
    }
}

/**
 * TEST ONLY: asserts that the migration of the [version] turns the [table] of a database
 * [createEarlier] makes into the one the schema declares.
 */
internal fun assertMigratesToSchema(
    table: String,
    version: Long,
    createEarlier: (JdbcSqliteDriver) -> Unit,
) {
    val created = openDriver().use { driver ->
        Database.Schema.create(driver)
        describeTable(driver, table)
    }
    val migrated = openDriver().use { driver ->
        createEarlier(driver)
        Database.Schema.migrate(driver, version, version + 1)
        describeTable(driver, table)
    }
    assertEquals(created, migrated)
}

/** TEST ONLY: asserts that the [syncIds] a migration gave are [count] random UUIDs, each its own. */
internal fun assertOwnSyncIds(count: Int, syncIds: List<String>) {
    assertEquals(count, syncIds.toSet().size)
    assertTrue(syncIds.all { UUID_REGEX.matches(it) }, syncIds.toString())
}

private fun pragma(
    driver: JdbcSqliteDriver,
    sql: String,
    columns: Int,
): List<String> = driver.executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<String>()
        while (cursor.next().value) {
            rows += (0 until columns)
                .joinToString(separator = "|") { index -> cursor.getString(index).orEmpty() }
        }
        QueryResult.Value(rows)
    },
    parameters = 0,
).value
