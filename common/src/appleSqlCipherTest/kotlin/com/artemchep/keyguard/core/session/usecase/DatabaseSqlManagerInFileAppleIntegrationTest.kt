package com.artemchep.keyguard.core.session.usecase

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.platform.withTestAppleStorage
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseSqlManagerInFileAppleIntegrationTest {
    private val oldKey = MasterKey(MasterKdfVersion.V1, ByteArray(32) { 0x11 })
    private val newKey = MasterKey(MasterKdfVersion.V1, ByteArray(32) { 0x22 })

    @Test
    fun databasesWithTheSameNameRemainIsolatedInSeparateFixtures() = runTest {
        withDatabase { first, _ ->
            val firstHelper = first.open(oldKey)
            try {
                firstHelper.driver.execute(null, "UPDATE entries SET value = 'first';", 0)
                withDatabase { second, _ ->
                    val secondHelper = second.open(oldKey)
                    try {
                        assertEquals("vault entry", secondHelper.driver.readEntry())
                        assertEquals("first", firstHelper.driver.readEntry())
                    } finally {
                        secondHelper.driver.close()
                    }
                }
            } finally {
                firstHelper.driver.close()
            }
        }
    }

    @Test
    fun encryptedDatabaseReopensAndRejectsWrongKeyWithoutLosingData() = runTest {
        withDatabase { manager, directory ->
            val helper = manager.open(oldKey)
            try {
                val version = helper.driver.executeQuery(null, "PRAGMA cipher_version;", { cursor ->
                    assertTrue(cursor.next().value, "SQLCipher must be linked for this suite")
                    QueryResult.Value(cursor.getString(0))
                }, 0).value
                assertFalse(version.isNullOrBlank())
                assertEquals("vault entry", helper.driver.readEntry())
            } finally {
                helper.driver.close()
            }
            val header = SystemFileSystem.source(Path(directory, DATABASE_NAME)).buffered().use {
                it.readByteArray(16)
            }
            assertFalse(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            assertFails { manager.open(newKey).driver.close() }
            val reopened = manager.open(oldKey)
            try {
                assertEquals("vault entry", reopened.driver.readEntry())
            } finally {
                reopened.driver.close()
            }
        }
    }

    @Test
    fun changePasswordPreservesDataAndReopensWithNewKey() = runTest {
        withDatabase { manager, _ ->
            val helper = manager.open(oldKey)
            try {
                assertEquals("vault entry", helper.driver.readEntry())
                helper.changePassword(newKey).bind()
                assertEquals("vault entry", helper.driver.readEntry())
            } finally {
                helper.driver.close()
            }
            val reopened = manager.open(newKey)
            try {
                assertEquals("vault entry", reopened.driver.readEntry())
            } finally {
                reopened.driver.close()
            }
            assertFails { manager.open(oldKey).driver.close() }
        }
    }

    @Test
    fun changePasswordRejectsBusyDatabaseAndKeepsOldKey() = runTest {
        withDatabase { manager, directory ->
            val helper = manager.open(oldKey)
            try {
                assertEquals("vault entry", helper.driver.readEntry())
                val connection = createDatabaseManager(
                    DatabaseConfiguration(
                        name = DATABASE_NAME,
                        version = NO_VERSION_CHECK,
                        create = {},
                        extendedConfig = DatabaseConfiguration.Extended(basePath = directory.toString()),
                        lifecycleConfig = DatabaseConfiguration.Lifecycle(
                            onCreateConnection = { connection ->
                                connection.rawExecSql("PRAGMA key = \"x'${oldKey.byteArray.toHex()}'\";")
                            },
                        ),
                    ),
                ).createMultiThreadedConnection()
                try {
                    // A separate connection bypasses the helper's in-process lock.
                    // Keep the write lock for the entire production busy timeout.
                    connection.rawExecSql("BEGIN IMMEDIATE;")
                    try {
                        assertFails(
                            "A busy database must reject the password change before new credentials are saved",
                        ) {
                            helper.changePassword(newKey).bind()
                        }
                    } finally {
                        connection.rawExecSql("ROLLBACK;")
                    }
                } finally {
                    connection.close()
                }
                assertEquals("vault entry", helper.driver.readEntry())
            } finally {
                helper.driver.close()
            }
            val reopened = manager.open(oldKey)
            try {
                assertEquals("vault entry", reopened.driver.readEntry())
            } finally {
                reopened.driver.close()
            }
        }
    }

    private suspend fun DatabaseSqlManagerInFileApple<SqlDriver>.open(key: MasterKey) = create(
        masterKey = key,
        databaseFactory = { it },
        databaseSchema = TestSchema,
    ).bind()

    private fun SqlDriver.readEntry(): String? = executeQuery(
        identifier = null,
        sql = "SELECT value FROM entries;",
        mapper = { cursor ->
            assertTrue(cursor.next().value)
            QueryResult.Value(cursor.getString(0))
        },
        parameters = 0,
    ).value

    private suspend fun withDatabase(
        block: suspend (DatabaseSqlManagerInFileApple<SqlDriver>, Path) -> Unit,
    ) {
        withTestAppleStorage { directories ->
            val manager = DatabaseSqlManagerInFileApple<SqlDriver>(
                directory = directories.data.path,
                fileName = DATABASE_NAME,
                onCreate = { ioUnit() },
            )
            block(manager, directories.data.path.toKotlinxIoPath())
        }
    }

    private object TestSchema : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE entries(value TEXT NOT NULL);", 0)
            driver.execute(null, "INSERT INTO entries VALUES ('vault entry');", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ) = QueryResult.Unit
    }

    private companion object {
        const val DATABASE_NAME = "vault.sqlite"
    }
}
