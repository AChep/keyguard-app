package com.artemchep.keyguard.common.service.database.exposed

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import java.util.Properties

/**
 * TEST ONLY: an [ExposedDatabaseManager] of the [database], in memory unless a test passes
 * its own. A change runs on it directly, and the password never changes.
 */
internal class TestExposedDatabaseManager(
    val database: DatabaseExposed = createExposedTestDatabase(),
) : ExposedDatabaseManager {
    override fun get(): IO<DatabaseExposed> = {
        database
    }

    override fun <T> mutate(
        tag: String,
        block: suspend (DatabaseExposed) -> T,
    ): IO<T> = {
        block(database)
    }

    override fun changePassword(
        newMasterKey: MasterKey,
    ): IO<Unit> = {}
}

/** TEST ONLY: a new empty database in memory that enforces foreign keys. */
internal fun createExposedTestDriver(): JdbcSqliteDriver {
    // Both production drivers enforce foreign keys — the JVM one
    // via this same property, the Android one via
    // setForeignKeyConstraintsEnabled — so the tests must run
    // under enforcement too.
    val properties = Properties().apply {
        put("foreign_keys", "true")
    }
    return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
}

/** TEST ONLY: a new exposed database on the [driver], with its tables; in memory by default. */
internal fun createExposedTestDatabase(
    driver: SqlDriver = createExposedTestDriver(),
): DatabaseExposed {
    DatabaseExposed.Schema.create(driver)
    return DatabaseExposed(
        driver = driver,
        urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
    )
}
