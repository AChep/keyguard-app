package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsImplTest {
    @Test
    fun `cancelling worker cancels active keepass watcher`() = runTest {
        val tokenRepository = TestServiceTokenRepository()
        val fileWatcherService = TestFileWatcherService()
        val worker = NotificationsImpl(
            tokenRepository = tokenRepository,
            logRepository = NoopLogRepository,
            deviceIdUseCase = DeviceIdUseCase(NoopIdRepository),
            base64Service = Base64ServiceImpl(),
            connectivityService = NoopConnectivityService,
            fileWatcherService = fileWatcherService,
            json = Json.Default,
            httpClient = HttpClient(MockEngine { respondOk() }),
            db = UnusedVaultDatabaseManager,
            queueSyncById = NoopQueueSyncById,
            queueSyncAll = NoopQueueSyncAll,
        )

        val workerJob = worker.launch(this)
        runCurrent()

        tokenRepository.set(listOf(keepassToken()))
        runCurrent()
        withTimeout(1_000L) {
            fileWatcherService.started.await()
        }

        workerJob.cancelAndJoin()

        withTimeout(1_000L) {
            fileWatcherService.cancelled.await()
        }
    }

    private fun keepassToken() = KeePassToken(
        id = "account-1",
        key = KeePassToken.Key(
            passwordBase64 = "password",
        ),
        files = KeePassToken.Files(
            databaseUri = "file:///tmp/account-1.kdbx",
        ),
    )
}

private class TestServiceTokenRepository : ServiceTokenRepository {
    private val tokens = MutableStateFlow<List<ServiceToken>>(emptyList())

    fun set(tokens: List<ServiceToken>) {
        this.tokens.value = tokens
    }

    override fun get(): Flow<List<ServiceToken>> = tokens

    override fun getById(id: AccountId): IO<ServiceToken?> = ioEffect {
        tokens.value.firstOrNull { it.id == id.id }
    }

    override fun put(model: ServiceToken): IO<Unit> = ioEffect {
        val updatedTokens = tokens.value
            .filterNot { it.id == model.id }
            .plus(model)
        tokens.value = updatedTokens
    }
}

private class TestFileWatcherService : FileWatcherService {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()

    override fun fileChangedFlow(file: LocalPath): Flow<FileWatchEvent> = flow {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancelled.complete(Unit)
        }
    }
}

private object NoopConnectivityService : ConnectivityService {
    override val availableFlow: Flow<Unit> = emptyFlow()

    override fun isInternetAvailable(): Boolean = true
}

private object NoopQueueSyncById : QueueSyncById {
    override fun invoke(accountId: AccountId): IO<Unit> = ioUnit()
}

private object NoopQueueSyncAll : QueueSyncAll {
    override fun invoke(): IO<Unit> = ioUnit()
}

private object NoopLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}

private object NoopIdRepository : IdRepository {
    override fun put(id: String): IO<Unit> = ioUnit()

    override fun get(): IO<String> = ioEffect {
        "device-id"
    }
}

private object UnusedVaultDatabaseManager : VaultDatabaseManager {
    override fun get(): IO<Database> = ioEffect {
        error("Vault database is not used in this test.")
    }

    override fun <T> mutate(
        tag: String,
        block: suspend (Database) -> T,
    ): IO<T> = ioEffect {
        error("Vault database is not used in this test.")
    }

    override fun changePassword(newMasterKey: MasterKey): IO<Unit> = ioEffect {
        error("Vault database is not used in this test.")
    }
}
