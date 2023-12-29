package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.DatabaseManagerImpl
import com.artemchep.keyguard.core.store.SqlManagerFile
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncByIdImpl
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import java.io.File

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

    bindSingleton<NotificationsWorker> {
        NotificationsImpl(this)
    }
    bindSingleton<DatabaseManager> {
        val dataDirectory: DataDirectory = instance()
        DatabaseManagerImpl(
            logRepository = instance(),
            json = instance(),
            masterKey = masterKey,
            sqlManager = SqlManagerFile(
                fileIo = dataDirectory
                    .data()
                    .effectMap {
                        File(it, "database.sqlite")
                    },
            ),
        )
    }
}
