package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.api.notificationsHub
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRetry
import io.ktor.client.HttpClient
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class NotificationsImpl(
    private val tokenRepository: BitwardenTokenRepository,
    private val base64Service: Base64Service,
    private val connectivityService: ConnectivityService,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
    private val queueSyncById: QueueSyncById,
    private val queueSyncAll: QueueSyncAll,
) : NotificationsWorker {
    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        base64Service = directDI.instance(),
        connectivityService = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
        queueSyncById = directDI.instance(),
        queueSyncAll = directDI.instance(),
    )

    private class FooBar(
        val key: Any,
        val job: Job,
    )

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        val subScope = this + SupervisorJob()

        fun launchJob(
            user: BitwardenToken,
        ): Job = subScope.launch {
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

                    withRetry {
                        // We must have internet access to connect to the
                        // notifications service.
                        connectivityService.awaitAvailable()

                        notificationsHub(
                            user = latestUser,
                            onMessage = {
                                val accountId = AccountId(latestUser.id)
                                queueSyncById(accountId)
                                    .launchIn(GlobalScope)
                            },
                        )
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

        // Connect to each account's notifications service. Try to
        // keep the not-changed accounts active.
        tokenRepository
            .get()
            .scan(
                initial = persistentMapOf<String, FooBar>(),
            ) { state, new ->
                var newState = state
                // Find if a new entry has a different
                // key comparing to the old one.
                new.forEach { account ->
                    val existingEntry = state[account.id]
                    if (existingEntry != null) {
                        val existingKey = existingEntry.key
                        if (existingKey == account) {
                            // keep the old entity.
                            return@forEach
                        }

                        // Cancel old job.
                        existingEntry.job.cancel()
                    }

                    val entry = FooBar(
                        key = account,
                        job = launchJob(account),
                    )
                    newState = newState.put(account.id, entry)
                }
                // Clean-up removed entries.
                newState.forEach { (id, _) ->
                    val removed = new.none { it.id == id }
                    if (removed) {
                        newState = newState.remove(id)
                    }
                }

                newState
            }
            .launchIn(this)

        // On start sync all the accounts.
        queueSyncAll()
            .attempt() // ignore crashes
            .launchIn(this)
    }
}
