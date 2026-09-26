package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.ExportManagerImpl
import com.artemchep.keyguard.core.session.GetSuggestionsImpl
import com.artemchep.keyguard.core.store.DatabaseSqlManagerInFileJvm
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.QueueSyncByIdImpl
import java.io.File
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
                    showMessage = get(),
                    context = get(),
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
                val dataDirectory: DataDirectory = get()
                val sqlManager = DatabaseSqlManagerInFileJvm<Database>(
                    fileIo = dataDirectory
                        .data()
                        .effectMap {
                            File(it, "database.sqlite")
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
                GetSuggestionsImpl()
            }
        }
    }
}
