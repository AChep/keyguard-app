package com.artemchep.keyguard.core.session.usecase

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.artemchep.keyguard.android.downloader.ExportManagerImpl
import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.copy.AndroidLinkInfoExtractorRegistry
import com.artemchep.keyguard.copy.QueueSyncAllAndroid
import com.artemchep.keyguard.copy.QueueSyncByIdAndroid
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.koin.dsl.module

class PlatformVaultModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<QueueSyncAll> {
                QueueSyncAllAndroid(
                    context = get<Application>(),
                    tokenRepository = get(),
                )
            }
            scoped<QueueSyncById> {
                QueueSyncByIdAndroid(
                    context = get(),
                    tokenRepository = get(),
                )
            }
            scoped<ExportManager> {
                ExportManagerImpl(
                    windowCoroutineScope = get(),
                    cryptoGenerator = get(),
                    exportVaultDataService = get(),
                    dirsService = get(),
                    zipService = get(),
                    dateFormatter = get(),
                    downloadSourceLoader = get(),
                    downloadAttachmentMetadata = get(),
                    vaultSessionLocker = get(),
                    context = get<Application>(),
                )
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
                val sqlManager = DatabaseSqlManagerInFileAndroid(
                    context = get<Application>(),
                    fileName = "database_v2",
                    onCreate = { database: Database ->
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
            scoped<GetSuggestions<Any?>> {
                GetSuggestionsImpl(
                    androidExtractors = get<AndroidLinkInfoExtractorRegistry>().values,
                    getAutofillDefaultMatchDetection = get(),
                    getUrlBlocks = get(),
                    cipherUrlCheck = get(),
                )
            }
        }
    }
}

class DatabaseSqlManagerInFileAndroid<Database>(
    private val context: Context,
    private val fileName: String,
    private val onCreate: (Database) -> IO<Unit>,
) : DatabaseSqlManager<Database> {
    init {
        System.loadLibrary("sqlcipher")
    }

    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): IO<DatabaseSqlHelper<Database>> = ioEffect {
        // Create encrypted database using the provided master key. If the
        // key is incorrect, trying to open the database would lead to a crash.
        val factory = SupportOpenHelperFactory(masterKey.byteArray, null, false)
        val openHelper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(DatabaseSqlManagerAndroidCallback(databaseSchema, *callbacks))
                .name(fileName)
                .noBackupDirectory(true)
                .build(),
        )
        // Opening SQLCipher through AndroidSqliteDriver is otherwise deferred until the first
        // query. Session repositories start together, so they can all block dispatcher threads on
        // the driver's synchronized database lazy while one thread performs the expensive open.
        // Open once while creating the shared helper, before those repository flows are released.
        val writableDatabase = openHelper.writableDatabase
        val driver: SqlDriver = AndroidSqliteDriver(writableDatabase)
        val database = databaseFactory(driver)
        onCreate(database)
            .bind()
        Helper(
            driver = driver,
            database = database,
            sqliteOpenHelper = openHelper,
        )
    }

    private class Helper<Database>(
        override val driver: SqlDriver,
        override val database: Database,
        private val sqliteOpenHelper: SupportSQLiteOpenHelper,
    ) : DatabaseSqlHelper<Database> {
        override fun changePassword(newMasterKey: MasterKey): IO<Unit> = ioEffect {
            val cipherDb = sqliteOpenHelper.writableDatabase as SQLiteDatabase
            cipherDb.changePassword(newMasterKey.byteArray)
        }
    }
}

class DatabaseSqlManagerInMemoryAndroid<Database>(
    private val context: Context,
    private val onCreate: (Database) -> IO<Unit>,
) : DatabaseSqlManager<Database> {
    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): IO<DatabaseSqlHelper<Database>> = ioEffect {
        val driver: SqlDriver = AndroidSqliteDriver(
            schema = databaseSchema,
            context = context,
            name = null,
            callback = DatabaseSqlManagerAndroidCallback(databaseSchema, *callbacks),
        )
        val database = databaseFactory(driver)
        onCreate(database)
            .bind()
        Helper(
            driver = driver,
            database = database,
        )
    }

    private class Helper<Database>(
        override val driver: SqlDriver,
        override val database: Database,
    ) : DatabaseSqlHelper<Database> {
        override fun changePassword(newMasterKey: MasterKey): IO<Unit> = ioUnit()
    }
}

private class DatabaseSqlManagerAndroidCallback(
    databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
    vararg callbacks: AfterVersion,
) : AndroidSqliteDriver.Callback(
    databaseSchema,
    *callbacks,
) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
