package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunner
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.platform.Platform
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                enabled = true,
            )
            runCurrent()

            assertNotNull(flushJob)
            assertTrue(flushJob.isCompleted)
            assertTrue(parent.isActive)
            assertTrue(sibling.isActive)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `disabled flush does not subscribe to the vault session`() = runTest {
        val getVaultSession = CountingGetVaultSession()

        val flushJob = launchPendingUsageHistoryFlushWhenAvailable(
            scope = backgroundScope,
            getVaultSession = getVaultSession,
            enabled = false,
        )

        assertNull(flushJob)
        assertEquals(0, getVaultSession.subscriptions)
    }

    @Test
    fun `pending usage history flush is disabled on Wear`() {
        val wear = Platform.Mobile.Android(
            isChromebook = false,
            isWatch = true,
            sdk = 35,
        )
        val phone = wear.copy(isWatch = false)

        assertFalse(shouldLaunchPendingUsageHistoryFlush(wear))
        assertTrue(shouldLaunchPendingUsageHistoryFlush(phone))
        assertTrue(shouldLaunchPendingUsageHistoryFlush(Platform.Desktop.Other))
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

private class CountingGetVaultSession : GetVaultSession {
    var subscriptions: Int = 0

    override val valueOrNull: MasterSession? = null

    override fun invoke(): Flow<MasterSession> {
        subscriptions += 1
        error("The disabled worker must not subscribe to the vault session.")
    }
}
