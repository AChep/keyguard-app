package com.artemchep.keyguard.android.worker.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.keyguardKoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

abstract class SessionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KeyguardKoinOwner {
    companion object {
        private const val SESSION_TIMEOUT_MS = 1000L
    }

    final override val koin get() = applicationContext.keyguardKoin()

    private val getVaultSession: GetVaultSession by lazy { koin.get() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun doWork(): Result = getVaultSession()
        .distinctUntilChanged()
        .flatMapLatest { session ->
            when (session) {
                is MasterSession.Key -> flow<Result> {
                    val result = doWork(session)
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

    abstract suspend fun doWork(session: MasterSession.Key): Result
}
