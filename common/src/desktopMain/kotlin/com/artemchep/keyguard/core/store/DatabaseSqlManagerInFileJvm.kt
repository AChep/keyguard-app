package com.artemchep.keyguard.core.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import io.ktor.util.*
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
        } catch (e: Exception) {
            e.printStackTrace()
            println(e.message)
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

    private suspend fun createSqlHelper(
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

        // Create or migrate the database schema.
        val targetVersion = databaseSchema.version
        val currentVersion = runCatching {
            driver.getCurrentVersion()
        }.getOrDefault(0L)
        if (currentVersion == 0L) {
            databaseSchema.create(driver)
        } else if (targetVersion > currentVersion) {
            databaseSchema.migrate(
                driver,
                currentVersion,
                targetVersion,
                *callbacks,
            )
        }
        // Bump the version to the current one.
        if (currentVersion != targetVersion) {
            driver.setCurrentVersion(targetVersion)
        }

        val database = databaseFactory(driver)
        return object : DatabaseSqlHelper<Database> {
            override val driver: SqlDriver get() = driver

            override val database: Database get() = database

            override fun changePassword(
                newMasterKey: MasterKey,
            ): IO<Unit> = ioEffect {
                val hex = hex(newMasterKey.byteArray)
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

    // Version

    private suspend fun SqlDriver.getCurrentVersion(): Long {
        val queryResult = executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                val version = cursor.getLong(0)
                requireNotNull(version)

                QueryResult.Value(version)
            },
            parameters = 0,
            binders = null,
        )
        return queryResult.await()
    }

    private suspend fun SqlDriver.setCurrentVersion(version: Long) {
        execute(null, "PRAGMA user_version = $version;", 0, null)
            .await()
    }
}
