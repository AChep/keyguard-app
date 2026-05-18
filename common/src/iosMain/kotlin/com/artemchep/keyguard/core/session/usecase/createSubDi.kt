package com.artemchep.keyguard.core.session.usecase

import arrow.optics.Getter
import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import com.artemchep.keyguard.provider.bitwarden.usecase.NotificationsImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

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
    bindProvider<CoroutineDispatcher>(tag = DatabaseDispatcher) {
        Dispatchers.IO
    }
    bindSingleton<LogRepository> {
        IosLogRepository
    }
    bindSingleton<Json> {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            prettyPrint = false
            isLenient = true
        }
    }
    bindSingleton<Base64Service> {
        Base64ServiceImpl()
    }
    bindSingleton<ConnectivityService> {
        IosAlwaysAvailableConnectivityService
    }
    bindSingleton<FileWatcherService> {
        IosNoOpFileWatcherService
    }
    bindSingleton<DeviceIdUseCase> {
        DeviceIdUseCase(IosInMemoryIdRepository())
    }
    bindSingleton<HttpClient> {
        val json: Json = instance()
        HttpClient(Darwin) {
            install(UserAgent) {
                agent = BitwardenPersona.of(CurrentPlatform)
                    .userAgent
            }
            install(ContentNegotiation) {
                register(ContentType.Application.Json, KotlinxSerializationConverter(json))
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
            install(HttpCache) {
                // In memory.
            }
            install(HttpRequestRetry) {
                configureBitwardenHttpRetry()
            }
        }
    }
    bindSingleton<NotificationsWorker> {
        NotificationsImpl(this)
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

private object IosAlwaysAvailableConnectivityService : ConnectivityService {
    override val availableFlow: Flow<Unit> = flowOf(Unit)

    override fun isInternetAvailable(): Boolean = true
}

private object IosLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        println("[${level.letter}]/$tag: $message")
    }
}

private object IosNoOpFileWatcherService : FileWatcherService {
    override fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent> = emptyFlow()
}

private class IosInMemoryIdRepository : IdRepository {
    private var id: String = ""

    override fun put(id: String): IO<Unit> = ioEffect {
        this.id = id
    }

    override fun get(): IO<String> = io(id)
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
