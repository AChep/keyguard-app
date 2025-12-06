package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.ExportManagerImpl
import com.artemchep.keyguard.core.session.GetSuggestionsImpl
import com.artemchep.keyguard.core.store.DatabaseSqlManagerInFileJvm
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.data.Database
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
    bindSingleton<ExportManager> {
        ExportManagerImpl(
            directDI = this,
        )
    }

    bindSingleton<NotificationsWorker> {
        NotificationsImpl(this)
    }
    bindSingleton<VaultDatabaseManager> {
        val dataDirectory: DataDirectory = instance()
        val sqlManager = DatabaseSqlManagerInFileJvm<Database>(
            fileIo = dataDirectory
                .data()
                .effectMap {
                    File(it, "database.sqlite")
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
        GetSuggestionsImpl()
    }
}
