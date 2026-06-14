package com.artemchep.keyguard.core.session.usecase

import arrow.optics.Getter
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.platform.toKotlinxIoPath
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncByIdImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

actual fun DI.Builder.createSubDi(
    masterKey: MasterKey,
) {
    createSubDi2(masterKey)

    bindSingleton<QueueSyncAll> {
        QueueSyncAllImpl(this)
    }
    bindSingleton<QueueSyncById> {
        QueueSyncByIdImpl(this)
    }
    bindSingleton<ExportManager> {
        IosUnsupportedExportManager
    }
    bindSingleton<ConnectivityService> {
        IosAlwaysAvailableConnectivityService
    }
    bindSingleton<FileWatcherService> {
        IosNoOpFileWatcherService
    }
    bindSingleton<NotificationsWorker> {
        NotificationsImpl(this)
    }
    bindSingleton<VaultDatabaseManager> {
        val sqlManager = DatabaseSqlManagerInFileIos<Database>(
            directory = iosKeyguardDataDirectory().resolve("vault"),
            fileName = "database_v2.sqlite",
            onCreate = { _: Database ->
                ioUnit()
            },
        )
        VaultDatabaseManagerImpl(
            logRepository = instance(),
            json = instance(),
            masterKey = masterKey,
            sqlManager = sqlManager,
        )
    }
    bindSingleton<GetSuggestions<Any?>> {
        object : GetSuggestions<Any?> {
            override fun invoke(
                items: List<Any?>,
                lens: Getter<Any?, DSecret>,
                target: AutofillTarget,
                equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
            ): IO<List<Any?>> = io(emptyList())
        }
    }
}

class DatabaseSqlManagerInFileIos<Database>(
    private val directory: LocalPath,
    private val fileName: String,
    private val onCreate: (Database) -> IO<Unit>,
) : DatabaseSqlManager<Database> {
    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): IO<DatabaseSqlHelper<Database>> = ioEffect {
        SystemFileSystem.createDirectories(directory.toKotlinxIoPath())

        fun openDriver(): SqlDriver {
            val rawKey = masterKey.sqlCipherRawKey()
            return NativeSqliteDriver(
                schema = databaseSchema,
                name = fileName,
                onConfiguration = { configuration ->
                    configuration.copy(
                        extendedConfig = configuration.extendedConfig.copy(
                            basePath = directory.value,
                            foreignKeyConstraints = true,
                        ),
                        lifecycleConfig = configuration.lifecycleConfig.copy(
                            onCreateConnection = { connection ->
                                connection.rawExecSql("PRAGMA key = \"$rawKey\";")
                                configuration.lifecycleConfig.onCreateConnection(connection)
                            },
                        ),
                    )
                },
                callbacks = callbacks,
            )
        }

        val driver = try {
            openDriver()
        } catch (e: Throwable) {
            if (isPlaintextSqliteDatabaseFile()) {
                deleteDatabaseFiles()
                openDriver()
            } else {
                throw e
            }
        }
        try {
            driver.touchDatabase()
            ensureEncryptedDatabaseFile()
        } catch (e: Throwable) {
            driver.close()
            if (isPlaintextSqliteDatabaseFile()) {
                deleteDatabaseFiles()
            }
            throw e
        }

        val database = databaseFactory(driver)
        onCreate(database).bind()
        Helper(
            driver = driver,
            database = database,
        )
    }

    private fun ensureEncryptedDatabaseFile() {
        val path = databaseFile().toKotlinxIoPath()
        require(SystemFileSystem.exists(path)) {
            "iOS vault database file was not created."
        }
        require(!isPlaintextSqliteDatabaseFile()) {
            "iOS vault database was created without SQLCipher encryption; refusing to continue."
        }
    }

    private fun isPlaintextSqliteDatabaseFile(): Boolean {
        val path = databaseFile().toKotlinxIoPath()
        if (!SystemFileSystem.exists(path)) {
            return false
        }

        val header = runCatching {
            SystemFileSystem.source(path)
                .buffered()
                .use { source ->
                    source.readByteArray(SQLITE_HEADER.size)
                }
        }.getOrNull() ?: return false

        return header.contentEquals(SQLITE_HEADER)
    }

    private fun deleteDatabaseFiles() {
        databaseFiles()
            .map { it.toKotlinxIoPath() }
            .forEach { path ->
                runCatching {
                    if (SystemFileSystem.exists(path)) {
                        SystemFileSystem.delete(path)
                    }
                }
            }
    }

    private fun databaseFile(): LocalPath = directory.resolve(fileName)

    private fun databaseFiles(): List<LocalPath> {
        val databaseFile = databaseFile()
        return listOf(
            databaseFile,
            LocalPath("${databaseFile.value}-journal"),
            LocalPath("${databaseFile.value}-shm"),
            LocalPath("${databaseFile.value}-wal"),
        )
    }

    private class Helper<Database>(
        override val driver: SqlDriver,
        override val database: Database,
    ) : DatabaseSqlHelper<Database> {
        override fun changePassword(
            newMasterKey: MasterKey,
        ): IO<Unit> = ioEffect {
            val key = newMasterKey.sqlCipherRawKey()
            driver.execute(
                identifier = null,
                sql = "PRAGMA rekey = \"$key\";",
                parameters = 0,
                binders = null,
            ).await()
        }
    }
}

private suspend fun SqlDriver.touchDatabase() {
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            cursor.getLong(0)
            QueryResult.Value(Unit)
        },
        parameters = 0,
        binders = null,
    ).await()
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()

private fun MasterKey.sqlCipherRawKey(): String = "x'${byteArray.toHex()}'"

private object IosAlwaysAvailableConnectivityService : ConnectivityService {
    override val availableFlow: Flow<Unit> = flowOf(Unit)

    override fun isInternetAvailable(): Boolean = true
}

private object IosNoOpFileWatcherService : FileWatcherService {
    override fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent> = emptyFlow()
}

private object IosUnsupportedExportManager : ExportManager {
    override fun getProgressFlowByExportId(
        exportId: String,
    ): Flow<Flow<DownloadProgress>?> = flowOf(null)

    override fun cancel(exportId: String) {
    }

    override suspend fun queue(
        request: ExportRequest,
    ): ExportManager.QueueResult {
        throw unsupported()
    }
}

private fun unsupported() = UnsupportedOperationException("Export is not supported on iOS yet.")
