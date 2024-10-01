package com.artemchep.keyguard.android.downloader.journal.room

import android.content.Context
import android.database.SQLException
import androidx.room.Room
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.retry
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.usecase.DeviceEncryptionKeyUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class DownloadDatabaseManager(
    private val applicationContext: Context,
    private val name: String,
    deviceEncryptionKeyUseCase: DeviceEncryptionKeyUseCase,
) {
    init {
        System.loadLibrary("sqlcipher")
    }

    private val dbMutex = Mutex()

    private val dbIo = deviceEncryptionKeyUseCase()
        .effectMap(Dispatchers.IO) { deviceKey ->
            val factory = SupportOpenHelperFactory(deviceKey, null, false)
            Room
                .databaseBuilder(
                    applicationContext,
                    DownloadDatabase::class.java,
                    name,
                )
                .openHelperFactory(factory)
                .addTypeConverter(DownloadDatabaseTypeConverter())
                .build()
                // Try to open the database, so, if we have any
                // major problems, it fails immediately.
                .also { db ->
                    db.openHelper.writableDatabase
                }
        }
        .retry { e, attempt ->
            // TODO: Would be nice to log this exception.
            val fatalException = e is IllegalStateException ||
                    e is SQLException
            if (fatalException && attempt == 0) {
                applicationContext.deleteDatabase(name)
                return@retry true
            }

            false
        }
        .shared("DownloadDatabaseManager")

    fun get() = dbIo

    fun <T> mutate(
        block: suspend (DownloadDatabase) -> T,
    ) = dbIo
        .effectMap(Dispatchers.IO) { db ->
            dbMutex.withLock {
                block(db)
            }
        }
}
