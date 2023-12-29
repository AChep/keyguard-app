package com.artemchep.keyguard.android.worker.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

abstract class SessionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), DIAware {
    companion object {
        private const val SESSION_TIMEOUT_MS = 1000L
    }

    final override val di by closestDI { applicationContext }

    private val getVaultSession: GetVaultSession by di.instance()

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun doWork(): Result = getVaultSession()
        .distinctUntilChanged()
        .flatMapLatest { session ->
            when (session) {
                is MasterSession.Key -> flow<Result> {
                    val result = session.di.doWork()
                    emit(result)
                }

                is MasterSession.Empty -> flow<Result> {
                    delay(SESSION_TIMEOUT_MS)
                    val result = Result.failure()
                    emit(result)
                }
            }
        }
        .first()

    abstract suspend fun DI.doWork(): Result
}
