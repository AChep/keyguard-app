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
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.copy.QueueSyncAllAndroid
import com.artemchep.keyguard.copy.QueueSyncByIdAndroid
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

actual fun DI.Builder.createSubDi(
    masterKey: MasterKey,
) {
    createSubDi2(masterKey)

    bindSingleton<QueueSyncAll> {
        QueueSyncAllAndroid(this)
    }
    bindSingleton<QueueSyncById> {
        QueueSyncByIdAndroid(this)
    }
    bindSingleton<ExportManager> {
        ExportManagerImpl(
            directDI = this,
        )
    }

    bindSingleton<NotificationsWorker> {
        NotificationsImpl(this)
    }
    bindSingleton<VaultDatabaseManager> {
        val sqlManager = DatabaseSqlManagerInFileAndroid<Database>(
            context = instance<Application>(),
            fileName = "database_v2",
            onCreate = { database ->
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
        GetSuggestionsImpl(
            directDI = this,
        )
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
                .callback(Callback(databaseSchema, *callbacks))
                .name(fileName)
                .noBackupDirectory(true)
                .build(),
        )
        val driver: SqlDriver = AndroidSqliteDriver(openHelper)
        val database = databaseFactory(driver)
        onCreate(database)
            .bind()
        Helper(
            driver = driver,
            database = database,
            sqliteOpenHelper = openHelper,
        )
    }

    private class Callback(
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
