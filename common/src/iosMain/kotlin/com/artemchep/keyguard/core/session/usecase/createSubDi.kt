package com.artemchep.keyguard.core.session.usecase

import arrow.optics.Getter
import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.data.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.bindSingleton

actual fun DI.Builder.createSubDi(
    masterKey: MasterKey,
) {
    createSubDi2(masterKey)

    bindSingleton<QueueSyncAll> {
        object : QueueSyncAll {
            override fun invoke(): IO<Unit> = ioUnit()
        }
    }
    bindSingleton<QueueSyncById> {
        object : QueueSyncById {
            override fun invoke(accountId: AccountId): IO<Unit> = ioUnit()
        }
    }
    bindSingleton<ExportManager> {
        IosUnsupportedExportManager
    }
    bindSingleton<NotificationsWorker> {
        object : NotificationsWorker {
            override fun launch(scope: CoroutineScope): Job = scope.launch {
            }
        }
    }
    bindSingleton<VaultDatabaseManager> {
        IosUnsupportedVaultDatabaseManager
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

private object IosUnsupportedVaultDatabaseManager : VaultDatabaseManager {
    override fun get(): IO<Database> = ioRaise(unsupported())

    override fun <T> mutate(
        tag: String,
        block: suspend (Database) -> T,
    ): IO<T> = ioRaise(unsupported())

    override fun changePassword(
        newMasterKey: MasterKey,
    ): IO<Unit> = ioRaise(unsupported())
}

private fun unsupported() = UnsupportedOperationException("Vault database is not supported on iOS yet.")
