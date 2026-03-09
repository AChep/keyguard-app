package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.canRetry
import com.artemchep.keyguard.common.util.getHttpCode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.api.notificationsHub
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.common.util.ReconnectFatalException
import com.artemchep.keyguard.common.util.withRunForever
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.net.URI
import kotlin.io.path.toPath

/**
 * @author Artem Chepurnyi
 */
class NotificationsImpl(
    private val tokenRepository: ServiceTokenRepository,
    private val logRepository: LogRepository,
    private val base64Service: Base64Service,
    private val connectivityService: ConnectivityService,
    private val fileWatcherService: FileWatcherService,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: VaultDatabaseManager,
    private val queueSyncById: QueueSyncById,
    private val queueSyncAll: QueueSyncAll,
) : NotificationsWorker {
    companion object {
        private const val TAG = "Notifications"

        private const val ACCOUNT_REMOVAL_DELAY_MS = 5_000L
    }

    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        logRepository = directDI.instance(),
        base64Service = directDI.instance(),
        connectivityService = directDI.instance(),
        fileWatcherService = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
        queueSyncById = directDI.instance(),
        queueSyncAll = directDI.instance(),
    )

    private class ActiveAccountEntry(
        val key: ServiceToken,
        val job: Job,
    )

    internal class ActiveAccountsController(
        private val scope: CoroutineScope,
        private val removalDelayMs: Long = ACCOUNT_REMOVAL_DELAY_MS,
        private val launchJob: (ServiceToken) -> Job,
    ) {
        private val mutex = Mutex()

        private val activeEntries = mutableMapOf<String, ActiveAccountEntry>()

        private val pendingRemovalJobs = mutableMapOf<String, Job>()

        suspend fun reconcile(accounts: List<ServiceToken>) {
            val jobsToCancel = mutableListOf<Job>()
            val accountIds = accounts
                .mapTo(mutableSetOf()) { it.id }

            mutex.withLock {
                accounts.forEach { account ->
                    pendingRemovalJobs
                        .remove(account.id)
                        ?.cancel()

                    val existingEntry = activeEntries[account.id]
                    if (existingEntry?.key == account) {
                        return@forEach
                    }

                    existingEntry?.job?.let(jobsToCancel::add)
                    activeEntries[account.id] = ActiveAccountEntry(
                        key = account,
                        job = launchJob(account),
                    )
                }

                activeEntries.entries
                    .toList()
                    .forEach { (id, entry) ->
                        val removed = id !in accountIds
                        if (!removed || id in pendingRemovalJobs) {
                            return@forEach
                        }

                        pendingRemovalJobs[id] = scope.launch {
                            delay(removalDelayMs)
                            cancelIfStillRemoved(id, entry)
                        }
                    }
            }

            jobsToCancel.forEach { it.cancel() }
        }

        private suspend fun cancelIfStillRemoved(
            id: String,
            entry: ActiveAccountEntry,
        ) {
            val self = currentCoroutineContext()[Job] ?: return
            var jobToCancel: Job? = null

            mutex.withLock {
                if (pendingRemovalJobs[id] !== self) {
                    return
                }

                pendingRemovalJobs.remove(id)
                val currentEntry = activeEntries[id]
                if (currentEntry === entry) {
                    activeEntries.remove(id)
                    jobToCancel = entry.job
                }
            }

            jobToCancel?.cancel()
        }
    }

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        val subScope = this + SupervisorJob()

        fun launchJob(
            user: BitwardenToken,
        ): Job = subScope.launch {
            fun launchSyncById(latestUser: BitwardenToken) {
                val accountId = AccountId(latestUser.id)
                queueSyncById(accountId)
                    .launchIn(GlobalScope)
            }

            val result = runCatching {
                withRefreshableAccessToken(
                    base64Service = base64Service,
                    httpClient = httpClient,
                    json = json,
                    db = db,
                    user = user,
                ) { latestUser ->
                    if (latestUser != user) {
                        // We give a chance for the database to update itself and
                        // emit the updated user once again. This delay should be
                        // large enough for the change to loop around and cancel
                        // this task. This delay should be small enough for a chance
                        // that there's no database update happening internally.
                        delay(1500L)
                    }

                    val reconnectBackoff = ReconnectBackoff()
                    reconnectBackoff.withRunForever {
                        // We must have internet access to connect to the
                        // notifications service.
                        connectivityService.awaitAvailable()

                        try {
                            notificationsHub(
                                user = latestUser,
                                onMessage = {
                                    this.reset()
                                    launchSyncById(latestUser)
                                },
                                onHeartbeat = {
                                    // After a successful re-connect we want to immediately
                                    // sync the account. We do not know what has happened in-between
                                    // the syncs.
                                    if (attempt > 0) {
                                        launchSyncById(latestUser)
                                    }

                                    this.reset()
                                },
                            )
                        } catch (e: Exception) {
                            val canReconnect = kotlin.run {
                                val statusCode = e.getHttpCode()
                                statusCode.canRetry()
                            }
                            if (!canReconnect) {
                                throw ReconnectFatalException(e)
                            }

                            throw e
                        }
                    }
                }
            }
            result.onFailure {
                if (it is CancellationException) {
                    return@onFailure
                }

                recordException(it)
            }
        }

        fun launchJob(
            user: KeePassToken,
        ): Job = subScope.launch {
            val databaseFile = runCatching {
                user.files.databaseUri
                    .let(::URI).toPath().toFile()
            }.getOrElse { e ->
                // This database URI is not supported, aborting
                // the watch service.
                val msg = "Skipping launching file watcher, database URI is not a file: $e"
                logRepository.post(TAG, msg)
                return@launch
            }

            val reconnectBackoff = ReconnectBackoff()
            reconnectBackoff.withRunForever {
                val dbChangedFlow = fileWatcherService.fileChangedFlow(databaseFile)
                    .filter { it.kind != FileWatchEvent.Kind.INITIALIZED }
                    .debounce(1000L)
                dbChangedFlow
                    .onEach {
                        this.reset()

                        val accountId = AccountId(user.id)
                        queueSyncById(accountId)
                            .launchIn(GlobalScope)
                    }
                    .collect()
            }
        }

        val accountsController = ActiveAccountsController(
            scope = subScope,
            launchJob = { account ->
                when (account) {
                    is BitwardenToken -> launchJob(account)
                    is KeePassToken -> launchJob(account)
                }
            },
        )

        // Connect to each account's notifications service. Try to
        // keep the not-changed accounts active.
        tokenRepository
            .get()
            .onEach { accountsController.reconcile(it) }
            .launchIn(this)

        // On start sync all the accounts.
        queueSyncAll()
            .attempt() // ignore crashes
            .launchIn(this)
    }
}
