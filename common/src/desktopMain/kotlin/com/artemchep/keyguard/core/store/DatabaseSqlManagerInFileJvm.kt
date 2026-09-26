package com.artemchep.keyguard.core.store

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.platform.recordException
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File
import java.sql.DriverManager
import java.util.*

class DatabaseSqlManagerInFileJvm<Database>(
    private val fileIo: IO<File>,
) : DatabaseSqlManager<Database> {
    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): IO<DatabaseSqlHelper<Database>> = ioEffect {
        val file = fileIo
            .bind()
        try {
            createSqlHelper(
                file = file,
                masterKey = masterKey,
                databaseFactory = databaseFactory,
                databaseSchema = databaseSchema,
                callbacks = callbacks,
            )
        } catch (e: DatabaseSchemaDowngradeException) {
            throw e
        } catch (e: Exception) {
            recordException(e)
            if ("is not a database" in e.message.orEmpty()) {
                file.delete()
            }

            // Try again
            createSqlHelper(
                file = file,
                masterKey = masterKey,
                databaseFactory = databaseFactory,
                databaseSchema = databaseSchema,
                callbacks = callbacks,
            )
        }
    }

    private fun createSqlHelper(
        file: File,
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): DatabaseSqlHelper<Database> {
        val driver: SqlDriver = createSqlDriver(
            file = file,
            key = masterKey.byteArray,
        )

        var initialized = false
        val database = try {
            driver.initializeSchema(databaseSchema, *callbacks)
            databaseFactory(driver).also { initialized = true }
        } finally {
            if (!initialized) {
                driver.close()
            }
        }
        return object : DatabaseSqlHelper<Database> {
            override val driver: SqlDriver get() = driver

            override val database: Database get() = database

            override fun changePassword(
                newMasterKey: MasterKey,
            ): IO<Unit> = ioEffect {
                val hex = newMasterKey.byteArray.toHex()
                // This is specific to a cipher that i'm using!
                // See:
                // https://github.com/Willena/sqlite-jdbc-crypt/blob/master/USAGE.md#encryption-key-manipulations
                // https://utelle.github.io/SQLite3MultipleCiphers/docs/configuration/config_sql_pragmas/#pragma-key
                driver.execute(
                    identifier = null,
                    sql = """
                        PRAGMA rekey = "x'$hex'";
                    """.trimIndent(),
                    parameters = 0,
                    binders = null,
                )
            }
        }
    }

    private fun createSqlDriver(
        file: File,
        key: ByteArray,
    ): SqlDriver {
        val drivers = DriverManager.getDrivers().toList()
        require(drivers.size == 1) {
            "There should be only one SQL driver, currently " +
                    drivers.joinToString { it::class.java.canonicalName } +
                    " are present."
        }

        val sqlCipherProps = SQLiteMCSqlCipherConfig.getDefault()
            .withRawUnsaltedKey(key)
            .build()
            .toProperties()
        val url = "jdbc:sqlite:file:${file.absolutePath}"
        return JdbcSqliteDriver(
            url = url,
            properties = Properties().apply {
                putAll(sqlCipherProps)
                put("foreign_keys", "true")
            },
        )
    }
}

internal fun SqlDriver.initializeSchema(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    vararg callbacks: AfterVersion,
) {
    val driver = this
    val transacter = object : TransacterImpl(driver) {}
    // JDBC transactions are thread-local. Keep the entire upgrade synchronous,
    // including callbacks and the version update, on the same connection.
    transacter.transaction {
        val currentVersion = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                check(cursor.next().value) { "Missing database schema version" }
                QueryResult.Value(requireNotNull(cursor.getLong(0)))
            },
            parameters = 0,
            binders = null,
        ).value
        val targetVersion = schema.version
        if (currentVersion > targetVersion) {
            throw DatabaseSchemaDowngradeException(currentVersion, targetVersion)
        }
        if (currentVersion == 0L) {
            schema.create(driver).value
        } else if (currentVersion < targetVersion) {
            schema.migrate(driver, currentVersion, targetVersion, *callbacks).value
        }
        if (currentVersion < targetVersion) {
            driver.execute(null, "PRAGMA user_version = $targetVersion;", 0, null).value
        }
    }
}

internal class DatabaseSchemaDowngradeException(
    val currentVersion: Long,
    val targetVersion: Long,
) : IllegalStateException(
    "The database downgrade is not supported: " +
            "$currentVersion > $targetVersion",
), NoAnalytics
