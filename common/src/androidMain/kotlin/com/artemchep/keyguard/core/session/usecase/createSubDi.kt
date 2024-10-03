package com.artemchep.keyguard.core.session.usecase

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.artemchep.keyguard.android.downloader.ExportManagerImpl
import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.copy.QueueSyncAllAndroid
import com.artemchep.keyguard.copy.QueueSyncByIdAndroid
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.DatabaseManagerImpl
import com.artemchep.keyguard.core.store.SqlHelper
import com.artemchep.keyguard.core.store.SqlManager
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
    bindSingleton<DatabaseManager> {
        val sqlManager: SqlManager = SqlManagerFile2(
            context = instance<Application>(),
            onCreate = { database ->
                ioUnit()
            },
        )

        DatabaseManagerImpl(
            logRepository = instance(),
            json = instance(),
            masterKey = masterKey,
            sqlManager = sqlManager,
        )
    }
}

class SqlManagerFile2(
    private val context: Context,
    private val onCreate: (Database) -> IO<Unit>,
) : SqlManager {
    init {
        System.loadLibrary("sqlcipher")
    }

    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        vararg callbacks: AfterVersion,
    ): IO<SqlHelper> = ioEffect {
        // Create encrypted database using the provided master key. If the
        // key is incorrect, trying to open the database would lead to a crash.
        val factory = SupportOpenHelperFactory(masterKey.byteArray, null, false)
        val openHelper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(Callback(*callbacks))
                .name("database_v2")
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
        vararg callbacks: AfterVersion,
    ) : AndroidSqliteDriver.Callback(
        Database.Schema,
        *callbacks,
    ) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    private class Helper(
        override val driver: SqlDriver,
        override val database: Database,
        private val sqliteOpenHelper: SupportSQLiteOpenHelper,
    ) : SqlHelper {
        override fun changePassword(newMasterKey: MasterKey): IO<Unit> = ioEffect {
            val cipherDb = sqliteOpenHelper.writableDatabase as SQLiteDatabase
            cipherDb.changePassword(newMasterKey.byteArray)
        }
    }
}
