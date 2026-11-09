package com.artemchep.keyguard.provider.keyguard.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyguardSyncTablesMigrationTest {
    private val tables = listOf(
        "p2pGroup",
        "p2pSyncEntry",
        "p2pPeerState",
        "p2pClock",
    )

    private val described = tables +
        listOf("generatorEmailRelay", "generatorWordlist", "generatorWordlistWord", "urlOverride")

    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `migration 28 creates the tables the schema declares`() {
        val created = openDriver().use { driver ->
            Database.Schema.create(driver)
            describe(driver)
        }
        val migrated = openDriver().use { driver ->
            createVersion28(driver)
            Database.Schema.migrate(driver, 28, 29)
            describe(driver)
        }
        assertEquals(created, migrated)
    }

    @Test
    fun `migration 28 gives every email forwarder its own sync id`() {
        openDriver().use { driver ->
            createVersion28(driver)
            listOf("A", "B", "C").forEachIndexed { index, name ->
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO generatorEmailRelay(id, name, type, data, createdAt) " +
                        "VALUES (${index + 1}, '$name', 'duckduckgo', '{}', $index)",
                    parameters = 0,
                )
            }
            Database.Schema.migrate(driver, 28, 29)
            val database = createUploadTestDatabase(driver)

            val rows = database.generatorEmailRelayQueries.getAll().executeAsList()
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.id }.sorted())
            assertEquals(listOf("A", "B", "C"), rows.sortedBy { it.id }.map { it.name })
            assertEquals(3, rows.map { it.syncId }.toSet().size)
            assertTrue(rows.all { uuid.matches(it.syncId) }, rows.map { it.syncId }.toString())
        }
    }

    @Test
    fun `migration 28 gives every wordlist its own sync id and keeps its words`() {
        openDriver().use { driver ->
            createVersion28(driver)
            listOf("A", "B", "C").forEachIndexed { index, name ->
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO generatorWordlist(id, name, createdAt) " +
                        "VALUES (${index + 1}, '$name', $index)",
                    parameters = 0,
                )
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO generatorWordlistWord(wordlistId, word) " +
                        "VALUES (${index + 1}, 'word$name')",
                    parameters = 0,
                )
            }
            Database.Schema.migrate(driver, 28, 29)
            val database = createUploadTestDatabase(driver)

            val rows = database.generatorWordlistQueries.getAll().executeAsList()
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.id }.sorted())
            assertEquals(listOf("A", "B", "C"), rows.sortedBy { it.id }.map { it.name })
            assertEquals(3, rows.map { it.syncId }.toSet().size)
            assertTrue(rows.all { uuid.matches(it.syncId) }, rows.map { it.syncId }.toString())
            assertTrue(rows.all { it.wordsHash == null })
            rows.forEach { row ->
                val words = database.generatorWordlistWordQueries
                    .getPrimitiveByWordlistId(row.id)
                    .executeAsList()
                assertEquals(listOf("word${row.name}"), words)
            }
        }
    }

    @Test
    fun `migration 28 gives every URL override its own sync id, except the built-in one`() {
        openDriver().use { driver ->
            createVersion28(driver)
            // The built-in override, renamed and turned off, comes after one of the user's,
            // and before a copy of its rule.
            insertUrlOverride(driver, id = 1, name = "Mine", regex = "^ftp:.*", command = "cmd://open {url}")
            insertUrlOverride(
                driver,
                id = 2,
                name = "HTTPS",
                regex = BUILT_IN_REGEX,
                command = BUILT_IN_COMMAND,
                enabled = 0,
            )
            insertUrlOverride(driver, id = 3, name = "Copy", regex = BUILT_IN_REGEX, command = BUILT_IN_COMMAND)
            Database.Schema.migrate(driver, 28, 29)
            val database = createUploadTestDatabase(driver)

            val syncIds = listOf(1L, 2L, 3L).associateWith { id ->
                database.urlOverrideQueries.getById(id).executeAsOne().syncId
            }
            assertNull(syncIds.getValue(2L))
            val synced = listOfNotNull(syncIds.getValue(1L), syncIds.getValue(3L))
            assertEquals(2, synced.toSet().size)
            assertTrue(synced.all { uuid.matches(it) }, synced.toString())
        }
    }

    @Test
    fun `migration 28 gives every URL override a sync id once the built-in one is gone`() {
        openDriver().use { driver ->
            createVersion28(driver)
            insertUrlOverride(driver, id = 1, name = "Mine", regex = "^ftp:.*", command = "cmd://open {url}")
            insertUrlOverride(driver, id = 2, name = "Edited", regex = "^http:.*", command = "https://example.com")
            Database.Schema.migrate(driver, 28, 29)
            val database = createUploadTestDatabase(driver)

            val syncIds = database.urlOverrideQueries.getSyncIds().executeAsList()
            assertEquals(2, syncIds.toSet().size)
            assertTrue(syncIds.all { uuid.matches(it) }, syncIds.toString())
        }
    }

    @Test
    fun `migrated tables hold the sync state and follow the group`() {
        openDriver().use { driver ->
            createVersion28(driver)
            Database.Schema.migrate(driver, 28, 29)
            val database = createUploadTestDatabase(driver)
            database.p2pGroupQueries.insert(
                groupId = "app",
                scope = "app",
                membership = "{}",
            )
            database.p2pSyncEntryQueries.upsert(
                groupId = "app",
                entryId = "entry",
                kind = "emailForwarder",
                versions = "[]",
                deletes = "[]",
            )
            database.p2pClockQueries.upsert(groupId = "app", wall = 1L, counter = 0L)
            database.p2pGroupQueries.updateRoster(roster = "{}", groupId = "app")
            assertEquals("{}", database.p2pGroupQueries.getByGroupId("app").executeAsOne().roster)
            assertEquals(1, database.p2pSyncEntryQueries.getByGroupId("app").executeAsList().size)
            // An entry is its kind and id: another kind under the same id is another entry.
            database.p2pSyncEntryQueries.upsert(
                groupId = "app",
                entryId = "entry",
                kind = "wordlist",
                versions = "[]",
                deletes = "[]",
            )
            assertEquals(2, database.p2pSyncEntryQueries.getByGroupId("app").executeAsList().size)
            val forwarder = database.p2pSyncEntryQueries
                .getByEntryId(groupId = "app", kind = "emailForwarder", entryId = "entry")
                .executeAsOne()
            assertEquals("emailForwarder", forwarder.kind)

            database.p2pGroupQueries.deleteByGroupId("app")

            assertTrue(database.p2pGroupQueries.getByGroupId("app").executeAsList().isEmpty())
            assertTrue(database.p2pSyncEntryQueries.getByGroupId("app").executeAsList().isEmpty())
            assertTrue(database.p2pClockQueries.getByGroupId("app").executeAsList().isEmpty())
        }
    }

    @Test
    fun `the group of linked apps owns its sync state`() {
        openDriver().use { driver ->
            Database.Schema.create(driver)
            val database = createUploadTestDatabase(driver)
            database.p2pGroupQueries.insert(
                groupId = "app",
                scope = "app",
                membership = "{}",
            )
            database.p2pClockQueries.upsert(groupId = "app", wall = 1L, counter = 0L)
            // Updating the membership keeps what the group owns.
            database.p2pGroupQueries.insert(
                groupId = "app",
                scope = "app",
                membership = """{"a":1}""",
            )
            assertEquals(1, database.p2pClockQueries.getByGroupId("app").executeAsList().size)

            database.p2pGroupQueries.deleteByGroupId("app")

            assertTrue(database.p2pClockQueries.getByGroupId("app").executeAsList().isEmpty())
        }
    }

    private fun openDriver() = JdbcSqliteDriver(
        url = JdbcSqliteDriver.IN_MEMORY,
        properties = Properties().apply {
            setProperty("foreign_keys", "true")
        },
    )

    /**
     * The schema of version 28: the current one without the sync tables, and with the email
     * forwarders, the wordlists and the URL overrides that had no sync ids yet. The URL
     * overrides start empty: a test adds the built-in one where it needs it.
     */
    private fun createVersion28(driver: JdbcSqliteDriver) {
        Database.Schema.create(driver)
        tables.reversed().forEach { table ->
            driver.execute(null, "DROP TABLE $table", 0)
        }
        driver.execute(null, "DROP TABLE generatorEmailRelay", 0)
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE generatorEmailRelay (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  type TEXT NOT NULL,
                  data TEXT NOT NULL,
                  createdAt INTEGER NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX idx_generatorEmailRelay_createdAt ON generatorEmailRelay(createdAt DESC)",
            parameters = 0,
        )
        // The words have no rows yet, so the wordlists drop with nothing to cascade to.
        driver.execute(null, "DROP TABLE generatorWordlist", 0)
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE generatorWordlist (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  createdAt INTEGER NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX idx_generatorWordlist_createdAt ON generatorWordlist(createdAt DESC)",
            parameters = 0,
        )
        createVersion28UrlOverrides(driver)
    }

    /** The columns, foreign keys and indexes of each described table. */
    private fun describe(driver: JdbcSqliteDriver): Map<String, List<String>> = described.associateWith { table ->
        pragma(driver, "PRAGMA table_info($table)", columns = TABLE_INFO_COLUMNS) +
            pragma(driver, "PRAGMA foreign_key_list($table)", columns = FOREIGN_KEY_LIST_COLUMNS) +
            pragma(driver, "PRAGMA index_list($table)", columns = INDEX_LIST_COLUMNS)
                // Skip the sequence number, which depends on the order of creation.
                .map { it.substringAfter('|') }
                .sorted()
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

    private companion object {
        const val TABLE_INFO_COLUMNS = 6
        const val FOREIGN_KEY_LIST_COLUMNS = 8
        const val INDEX_LIST_COLUMNS = 5

        /** The rule of the built-in URL override. */
        const val BUILT_IN_REGEX = "^http:.*"
        const val BUILT_IN_COMMAND = "https://{url:rmvscm}"
    }
}

private fun insertUrlOverride(
    driver: JdbcSqliteDriver,
    id: Long,
    name: String,
    regex: String,
    command: String,
    enabled: Long = 1,
) {
    driver.execute(
        identifier = null,
        sql = "INSERT INTO urlOverride(id, name, regex, command, createdAt, enabled) VALUES (?, ?, ?, ?, ?, ?)",
        parameters = 6,
    ) {
        bindLong(0, id)
        bindString(1, name)
        bindString(2, regex)
        bindString(3, command)
        bindLong(4, id)
        bindLong(5, enabled)
    }
}

/** The URL overrides of version 28, which had no sync ids yet; empty. */
private fun createVersion28UrlOverrides(driver: JdbcSqliteDriver) {
    driver.execute(null, "DROP TABLE urlOverride", 0)
    driver.execute(
        identifier = null,
        sql = """
            CREATE TABLE urlOverride (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              regex TEXT NOT NULL,
              command TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              enabled INTEGER NOT NULL
            )
        """.trimIndent(),
        parameters = 0,
    )
    driver.execute(
        identifier = null,
        sql = "CREATE INDEX idx_urlOverride_createdAt ON urlOverride(createdAt DESC)",
        parameters = 0,
    )
}
