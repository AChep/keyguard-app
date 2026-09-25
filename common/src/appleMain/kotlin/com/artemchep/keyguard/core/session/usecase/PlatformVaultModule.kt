package com.artemchep.keyguard.core.session.usecase

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.withConnection
import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.impl.ExportManagerBase
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.appleKeyguardDataDirectory
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncByIdImpl
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.koin.dsl.module

class PlatformVaultModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<QueueSyncAll> {
                QueueSyncAllImpl(
                    syncAll = get(),
                )
            }
            scoped<QueueSyncById> {
                QueueSyncByIdImpl(
                    syncById = get(),
                )
            }
            scoped<ExportManager> {
                ExportManagerBase(
                    windowCoroutineScope = get(),
                    cryptoGenerator = get(),
                    exportVaultDataService = get(),
                    dirsService = get(),
                    zipService = get(),
                    dateFormatter = get(),
                    downloadSourceLoader = get(),
                    downloadAttachmentMetadata = get(),
                    vaultSessionLocker = get(),
                    onLaunch = {},
                )
            }
            scoped<ConnectivityService> {
                AppleAlwaysAvailableConnectivityService
            }
            scoped<FileWatcherService> {
                AppleNoOpFileWatcherService
            }
            scoped<NotificationsWorker> {
                NotificationsImpl(
                    tokenRepository = get(),
                    logRepository = get(),
                    deviceIdUseCase = get(),
                    base64Service = get(),
                    connectivityService = get(),
                    fileWatcherService = get(),
                    json = get(),
                    httpClient = get(),
                    db = get(),
                    queueSyncById = get(),
                    queueSyncAll = get(),
                )
            }
            scoped<VaultDatabaseManager> {
                val sqlManager = DatabaseSqlManagerInFileApple<Database>(
                    directory = appleKeyguardDataDirectory().resolve("vault"),
                    fileName = "database_v2.sqlite",
                    onCreate = { _: Database ->
                        ioUnit()
                    },
                )
                VaultDatabaseManagerImpl(
                    logRepository = get(),
                    json = get(),
                    masterKey = get(),
                    sqlManager = sqlManager,
                )
            }
            // Real suggestion matching (the impl now lives in commonMain): matches the
            // requested service identifiers to login ciphers via CipherUrlCheck + equivalent
            // domains. Drives the iOS AutoFill manual picker (and the macOS picker). The
            // Android-specific link extractors it would resolve are simply absent here
            // leaving the web/host matching path.
            scoped<GetSuggestions<Any?>> {
                GetSuggestionsImpl(
                    getAutofillDefaultMatchDetection = get(),
                    getUrlBlocks = get(),
                    cipherUrlCheck = get(),
                )
            }
        }
    }
}

class DatabaseSqlManagerInFileApple<Database>(
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

        fun DatabaseConfiguration.withVaultKey(key: MasterKey): DatabaseConfiguration {
            val rawKey = key.sqlCipherRawKey()
            return copy(
                extendedConfig = extendedConfig.copy(
                    basePath = directory.value,
                    foreignKeyConstraints = true,
                ),
                lifecycleConfig = lifecycleConfig.copy(
                    onCreateConnection = { connection ->
                        try {
                            connection.rawExecSql("PRAGMA key = \"$rawKey\";")
                            // PRAGMA key alone does not check whether the key can
                            // decrypt the database. Force a read on this connection.
                            connection.rawExecSql("SELECT count(*) FROM sqlite_master;")
                            lifecycleConfig.onCreateConnection(connection)
                        } catch (e: Throwable) {
                            // SQLiter does not close connections when this callback fails.
                            connection.close()
                            throw e
                        }
                    },
                ),
            )
        }

        fun openDriver(key: MasterKey): SqlDriver = NativeSqliteDriver(
            schema = databaseSchema,
            name = fileName,
            onConfiguration = { it.withVaultKey(key) },
            callbacks = callbacks,
        )

        // Bypasses SQLDelight's pools: its PRAGMA query pool is read-only,
        // and its execute() rejects SQLCipher's status row.
        fun openWritableDatabase(key: MasterKey) = createDatabaseManager(
            DatabaseConfiguration(
                name = fileName,
                version = NO_VERSION_CHECK,
                create = {},
            ).withVaultKey(key),
        )

        val initialDriver = try {
            openDriver(masterKey)
        } catch (e: Throwable) {
            if (isPlaintextSqliteDatabaseFile()) {
                deleteDatabaseFiles()
                openDriver(masterKey)
            } else {
                throw e
            }
        }
        val driver = RekeyableAppleSqlDriver(
            initialDriver = initialDriver,
            initialKey = masterKey,
            openDriver = ::openDriver,
            rekeyDatabase = { oldKey, newKey ->
                openWritableDatabase(oldKey).withConnection { connection ->
                    connection.rawExecSql("PRAGMA rekey = \"${newKey.sqlCipherRawKey()}\";")
                }
                // SQLCipher can report success even when rekey fails (e.g. SQLITE_BUSY).
                // Opening a fresh connection verifies the new key before the wrapper
                // adopts it and the caller persists the new credentials.
                openWritableDatabase(newKey).withConnection { }
            },
            openTransactionConnection = { key ->
                openWritableDatabase(key).createMultiThreadedConnection()
            },
        )
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
        override val driver: RekeyableAppleSqlDriver,
        override val database: Database,
    ) : DatabaseSqlHelper<Database> {
        override fun changePassword(
            newMasterKey: MasterKey,
        ): IO<Unit> = ioEffect {
            driver.rekey(newMasterKey)
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

private object AppleAlwaysAvailableConnectivityService : ConnectivityService {
    override val availableFlow: Flow<Unit> = flowOf(Unit)

    override fun isInternetAvailable(): Boolean = true
}

private object AppleNoOpFileWatcherService : FileWatcherService {
    override fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent> = emptyFlow()
}
