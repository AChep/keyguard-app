package com.artemchep.keyguard.core.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.data.Database
import io.ktor.util.hex
import java.io.File
import java.util.Properties

class SqlManagerFile(
    private val fileIo: IO<File>,
) : SqlManager {
    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
    ): IO<SqlHelper> = ioEffect {
        val driver: SqlDriver = createSqlDriver(
            file = fileIo
                .bind(),
            key = masterKey.byteArray,
        )

        // Create or migrate the database schema.
        val targetVersion = Database.Schema.version
        val currentVersion = runCatching {
            driver.getCurrentVersion()
        }.getOrDefault(0L)
        if (currentVersion == 0L) {
            Database.Schema.create(driver)
        } else if (targetVersion > currentVersion) {
            Database.Schema.migrate(driver, currentVersion, targetVersion)
        }
        // Bump the version to the current one.
        if (currentVersion != targetVersion) {
            driver.setCurrentVersion(targetVersion)
        }

        val database = databaseFactory(driver)
        object : SqlHelper {
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
                        PRAGMA key = "x'$hex";
                        PRAGMA rekey = "x'$hex";
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
        val hex = hex(key)
        val url = "jdbc:sqlite:file:${file.absolutePath}"
        return JdbcSqliteDriver(
            url = url,
            properties = Properties().apply {
                put("cipher", "sqlcipher")
                put("hexkey", hex)
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
