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
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsImplTest {
    @Test
    fun `keepass watcher receives bookmark and debounces changes without syncing initialization`() = runTest {
        withWorker {
            tokenRepository.set(listOf(keepassToken(accessToken = "bookmark")))
            runCurrent()
            val subscription = fileWatcherService.subscriptions.single()
            assertEquals("file:///tmp/account-1.kdbx", subscription.uri)
            assertEquals(FileAccessToken("bookmark"), subscription.accessToken)

            subscription.send(FileWatchEvent.Kind.INITIALIZED)
            runCurrent()
            advanceTimeBy(1_001L)
            runCurrent()
            assertTrue(queueSyncById.accounts.isEmpty())

            subscription.send(FileWatchEvent.Kind.MODIFIED)
            runCurrent()
            advanceTimeBy(600L)
            subscription.send(FileWatchEvent.Kind.DELETED)
            subscription.send(FileWatchEvent.Kind.CREATED)
            runCurrent()
            advanceTimeBy(999L)
            runCurrent()
            assertTrue(queueSyncById.accounts.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(AccountId("account-1")), queueSyncById.accounts)
        }
    }

    @Test
    fun `cancelling worker cancels active keepass watcher and pending sync`() = runTest {
        withWorker {
            tokenRepository.set(listOf(keepassToken()))
            runCurrent()
            val subscription = fileWatcherService.subscriptions.single()
            assertEquals(null, subscription.accessToken)
            subscription.send(FileWatchEvent.Kind.MODIFIED)
            runCurrent()

            workerJob.cancelAndJoin()
            assertTrue(subscription.cancelled)
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue(queueSyncById.accounts.isEmpty())
        }
    }

    @Test
    fun `changing a keepass bookmark or uri replaces its watcher`() = runTest {
        withWorker {
            tokenRepository.set(listOf(keepassToken(accessToken = "first-bookmark")))
            runCurrent()
            val original = fileWatcherService.subscriptions.single()

            tokenRepository.set(listOf(keepassToken(accessToken = "renewed-bookmark")))
            runCurrent()
            assertTrue(original.cancelled)
            val renewed = fileWatcherService.subscriptions.last()
            assertEquals(FileAccessToken("renewed-bookmark"), renewed.accessToken)

            val movedUri = "file:///tmp/moved.kdbx"
            tokenRepository.set(listOf(keepassToken(uri = movedUri, accessToken = "renewed-bookmark")))
            runCurrent()
            assertTrue(renewed.cancelled)
            val moved = fileWatcherService.subscriptions.last()
            assertEquals(movedUri, moved.uri)
            assertFalse(moved.cancelled)
            assertEquals(3, fileWatcherService.subscriptions.size)

            moved.send(FileWatchEvent.Kind.MODIFIED)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf(AccountId("account-1")), queueSyncById.accounts)
        }
    }

    @Test
    fun `removing a keepass account cancels its watcher after the removal grace period`() = runTest {
        withWorker {
            tokenRepository.set(listOf(keepassToken()))
            runCurrent()
            val subscription = fileWatcherService.subscriptions.single()
            tokenRepository.set(emptyList())
            runCurrent()

            advanceTimeBy(4_999L)
            runCurrent()
            assertFalse(subscription.cancelled)
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(subscription.cancelled)
        }
    }

    @Test
    fun `an account returning during removal grace keeps the original watcher`() = runTest {
        withWorker {
            val token = keepassToken()
            tokenRepository.set(listOf(token))
            runCurrent()
            val subscription = fileWatcherService.subscriptions.single()
            tokenRepository.set(emptyList())
            runCurrent()
            advanceTimeBy(2_000L)

            tokenRepository.set(listOf(token))
            runCurrent()
            advanceTimeBy(5_000L)
            runCurrent()
            assertFalse(subscription.cancelled)
            assertEquals(1, fileWatcherService.subscriptions.size)
        }
    }

    private fun keepassToken(
        uri: String = "file:///tmp/account-1.kdbx",
        accessToken: String? = null,
    ) = KeePassToken(
        id = "account-1",
        key = KeePassToken.Key(
            passwordBase64 = "password",
        ),
        database = KeePassToken.Database(
            fileName = "account-1.kdbx",
            location = FileLocation.Local(
                uri = uri,
                accessToken = accessToken,
                displayName = "account-1.kdbx",
            ),
        ),
    )
}

private class NotificationsFixture(
    scope: TestScope,
) {
    val tokenRepository = TestServiceTokenRepository()
    val fileWatcherService = TestFileWatcherService()
    val queueSyncById = RecordingQueueSyncById()
    val httpClient = HttpClient(MockEngine { respondOk() })
    val workerJob: Job = NotificationsImpl(
        tokenRepository = tokenRepository,
        logRepository = NoopLogRepository,
        deviceIdUseCase = DeviceIdUseCase(NoopIdRepository),
        base64Service = Base64ServiceImpl(),
        connectivityService = NoopConnectivityService,
        fileWatcherService = fileWatcherService,
        json = Json.Default,
        httpClient = httpClient,
        db = UnusedVaultDatabaseManager,
        queueSyncById = queueSyncById,
        queueSyncAll = NoopQueueSyncAll,
    ).launch(scope.backgroundScope)
}

private suspend fun TestScope.withWorker(
    block: suspend NotificationsFixture.() -> Unit,
) {
    val fixture = NotificationsFixture(this)
    try {
        fixture.block()
    } finally {
        fixture.workerJob.cancelAndJoin()
        fixture.httpClient.close()
    }
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
    val subscriptions = mutableListOf<Subscription>()

    override fun fileChangedFlow(file: LocalPath): Flow<FileWatchEvent> =
        error("KeePass notifications must pass the URI and access token.")

    override fun uriChangedFlow(
        uri: String,
        accessToken: FileAccessToken?,
    ): Flow<FileWatchEvent> = flow {
        val subscription = Subscription(uri, accessToken)
        subscriptions += subscription
        try {
            emitAll(subscription.events.receiveAsFlow())
        } finally {
            subscription.cancelled = true
            subscription.events.cancel()
        }
    }

    class Subscription(
        val uri: String,
        val accessToken: FileAccessToken?,
    ) {
        val events = Channel<FileWatchEvent>(Channel.UNLIMITED)
        var cancelled = false

        fun send(kind: FileWatchEvent.Kind) {
            check(events.trySend(FileWatchEvent(LocalPath("/tmp/account-1.kdbx"), kind, null)).isSuccess)
        }
    }
}

private object NoopConnectivityService : ConnectivityService {
    override val availableFlow: Flow<Unit> = emptyFlow()

    override fun isInternetAvailable(): Boolean = true
}

private class RecordingQueueSyncById : QueueSyncById {
    val accounts = mutableListOf<AccountId>()

    override fun invoke(accountId: AccountId): IO<Unit> = ioEffect {
        accounts += accountId
    }
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
