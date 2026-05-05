package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AccountSyncScheduler(
    private val scope: CoroutineScope,
    private val sync: (ServiceToken) -> IO<Unit>,
) {
    private val mutex = Mutex()
    private val accounts = mutableMapOf<String, AccountState>()

    fun enqueue(token: ServiceToken): IO<Unit> = {
        val run = enqueueRun(token)
        run.result.await()
    }

    private suspend fun enqueueRun(
        token: ServiceToken,
    ): ScheduledRun = mutex.withLock {
        val state = accounts.getOrPut(token.id) { AccountState() }
        val active = state.active
        if (active == null) {
            val run = ScheduledRun(token = token)
            state.active = run
            launchRun(accountId = token.id, run = run)
            return@withLock run
        }

        val pending = state.pending
        if (pending != null) {
            pending.token = token
            return@withLock pending
        }

        ScheduledRun(token = token)
            .also { run ->
                state.pending = run
            }
    }

    private fun launchRun(
        accountId: String,
        run: ScheduledRun,
    ) {
        val token = run.token
        scope.launch {
            try {
                sync(token).bind()
                run.result.complete(Unit)
            } catch (e: Throwable) {
                run.result.completeExceptionally(e)
            } finally {
                finishRun(accountId = accountId, run = run)
            }
        }
    }

    private suspend fun finishRun(
        accountId: String,
        run: ScheduledRun,
    ) {
        mutex.withLock {
            val state = accounts[accountId] ?: return@withLock
            if (state.active !== run) {
                return@withLock
            }

            val pending = state.pending
            if (pending != null) {
                state.pending = null
                state.active = pending
                launchRun(accountId = accountId, run = pending)
            } else {
                accounts.remove(accountId)
            }
        }
    }

    private class AccountState {
        var active: ScheduledRun? = null
        var pending: ScheduledRun? = null
    }

    private class ScheduledRun(
        var token: ServiceToken,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
    )
}
