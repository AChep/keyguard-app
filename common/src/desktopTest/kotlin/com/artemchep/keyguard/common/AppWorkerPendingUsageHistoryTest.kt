package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunner
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppWorkerPendingUsageHistoryTest {
    @Test
    fun `flush failure does not cancel sibling lifecycle work`() = runTest {
        val session = MasterSession.Key(
            masterKey = MasterKey(
                version = MasterKdfVersion.V0,
                byteArray = byteArrayOf(1, 2, 3),
            ),
            di = DI {
                bindSingleton<PendingUsageHistoryFlushRunner> {
                    FailingPendingUsageHistoryFlushRunner
                }
            },
            origin = MasterSession.Key.Authenticated,
            createdAt = Instant.fromEpochMilliseconds(1L),
        )
        val parent = Job()
        val scope = CoroutineScope(
            parent + StandardTestDispatcher(testScheduler),
        )
        val sibling = scope.launch {
            awaitCancellation()
        }

        try {
            val flushJob = launchPendingUsageHistoryFlushWhenAvailable(
                scope = scope,
                getVaultSession = FixedGetVaultSession(session),
            )
            runCurrent()

            assertTrue(flushJob.isCompleted)
            assertTrue(parent.isActive)
            assertTrue(sibling.isActive)
        } finally {
            parent.cancel()
        }
    }
}

private object FailingPendingUsageHistoryFlushRunner : PendingUsageHistoryFlushRunner {
    override fun run(): IO<Unit> = ioEffect {
        error("flush failed")
    }
}

private class FixedGetVaultSession(
    private val session: MasterSession,
) : GetVaultSession {
    override val valueOrNull: MasterSession = session

    override fun invoke(): Flow<MasterSession> = flowOf(session)
}
