package com.artemchep.keyguard.integration.p2p

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.data.Database
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Small stand-ins for the app services that linked apps need but these tests do not.

internal object NoopLogRepository : LogRepository {
    override fun post(tag: String, message: String, level: LogLevel) = Unit

    override suspend fun add(tag: String, message: String, level: LogLevel) = Unit
}

/** Keeps the latest warnings and errors, such as failed sync passes, for diagnostics. */
internal class RecentLogRepository : LogRepository {
    private val lines = mutableListOf<String>()

    fun lines(): List<String> = synchronized(lines) { lines.toList() }

    fun record(line: String) {
        synchronized(lines) {
            lines += line
            if (lines.size > SIZE) lines.removeAt(0)
        }
    }

    override fun post(tag: String, message: String, level: LogLevel) {
        if (level >= LogLevel.WARNING) record("$level $tag: $message")
    }

    override suspend fun add(tag: String, message: String, level: LogLevel) = post(tag, message, level)

    private companion object {
        const val SIZE = 40
    }
}

/** Every test device is online; the relay tells whether it is reachable. */
internal object OnlineConnectivityService : ConnectivityService {
    override val availableFlow: Flow<Unit> = flowOf(Unit)

    override fun isInternetAvailable(): Boolean = true
}

/** Linking apps needs Premium. */
internal object PurchasedPremium : GetPurchased {
    override fun invoke(): Flow<Premium> = flowOf(Premium.PURCHASED)
}

/** Keeps the helper of the database it creates, so the driver can be closed. */
internal class ClosingSqlManager(
    private val delegate: DatabaseSqlManager<Database>,
) : DatabaseSqlManager<Database> {
    @Volatile
    private var helper: DatabaseSqlHelper<Database>? = null

    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): IO<DatabaseSqlHelper<Database>> = ioEffect {
        delegate
            .create(masterKey, databaseFactory, databaseSchema, *callbacks)
            .bind()
            .also { helper = it }
    }

    fun close() {
        helper?.driver?.close()
    }
}
