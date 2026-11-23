package com.artemchep.keyguard.desktop.startup

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopForegroundServicesTest {
    @Test
    fun mainApprovalAndQuickSearchShareTheActualProcessLifecycleAndLockTimer() = runTest {
        var nextId = 0
        val crypto = Proxy.newProxyInstance(
            CryptoGenerator::class.java.classLoader,
            arrayOf(CryptoGenerator::class.java),
        ) { _, method, _ ->
            check(method.name == "uuid")
            (++nextId).toString()
        } as CryptoGenerator
        val provider = LePlatformLifecycleProvider(backgroundScope, crypto)
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        var refreshes = 0
        locker.start()
        provider.lifecycleStateFlow.withDesktopForegroundServices(locker::keepAlive) {
            refreshes++
        }.launchIn(backgroundScope)
        runCurrent()
        advanceTimeBy(5.seconds)
        val main = MutableStateFlow(LeLifecycleState.RESUMED)
        val approval = MutableStateFlow(LeLifecycleState.RESUMED)
        val quickSearch = MutableStateFlow(LeLifecycleState.RESUMED)
        val removeMain = provider.register(main)
        val removeApproval = provider.register(approval)
        val removeSearch = provider.register(quickSearch)
        runCurrent()
        assertEquals(1, refreshes)
        removeMain()
        removeApproval()
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)
        repeat(10) {
            quickSearch.value = LeLifecycleState.STARTED
            runCurrent()
            advanceTimeBy(100)
            quickSearch.value = LeLifecycleState.RESUMED
            runCurrent()
        }
        assertEquals(11, refreshes)
        assertEquals(emptyList(), locks)
        removeSearch()
        runCurrent()
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun refreshRunsWhileForegroundLeaseIsHeldAndAgainOnReactivation() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.CREATED)
        var leases = 0
        var refreshes = 0
        lifecycle.withDesktopForegroundServices(
            keepAlive = {
                leases++
                try { awaitCancellation() } finally { leases-- }
            },
            refreshStartup = { refreshes++ },
        ).launchIn(backgroundScope)
        runCurrent()
        assertEquals(0, refreshes)
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        assertEquals(1, leases)
        assertEquals(1, refreshes)
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        assertEquals(1, refreshes)
        lifecycle.value = LeLifecycleState.STARTED
        runCurrent()
        assertEquals(0, leases)
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        assertEquals(1, leases)
        assertEquals(2, refreshes)
    }

    @Test
    fun slowRefreshDoesNotAllowLockingAndIsCancelledInBackground() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.CREATED)
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        var cancellations = 0
        locker.start() // A restored session starts its timer before any window opens.
        lifecycle.withDesktopForegroundServices(locker::keepAlive) {
            try { awaitCancellation() } finally { cancellations++ }
        }.launchIn(backgroundScope)
        runCurrent()
        advanceTimeBy(5.seconds)
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)
        lifecycle.value = LeLifecycleState.STARTED
        runCurrent()
        assertEquals(1, cancellations)
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun failedRefreshKeepsLeaseAndCanRefreshOnNextForeground() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.RESUMED)
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        var attempts = 0
        lifecycle.withDesktopForegroundServices(locker::keepAlive) {
            attempts++
            if (attempts == 1) error("OS status query failed")
        }.launchIn(backgroundScope)
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)
        lifecycle.value = LeLifecycleState.STARTED
        runCurrent()
        advanceTimeBy(500)
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(2, attempts)
        assertEquals(emptyList(), locks)
    }

    @Test
    fun cancellingProcessServicesReleasesTheLease() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.RESUMED)
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        val job = lifecycle.withDesktopForegroundServices(locker::keepAlive) {
            awaitCancellation()
        }.launchIn(backgroundScope)
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun refreshCancellationEndsOnlyThatRefresh() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.RESUMED)
        var leases = 0
        var refreshes = 0
        val job = lifecycle.withDesktopForegroundServices(
            keepAlive = {
                leases++
                try { awaitCancellation() } finally { leases-- }
            },
            refreshStartup = {
                refreshes++
                throw CancellationException("refresh cancelled")
            },
        ).launchIn(backgroundScope)
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(1, leases)
        lifecycle.value = LeLifecycleState.STARTED
        runCurrent()
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        assertEquals(1, leases)
        assertEquals(2, refreshes)
    }

    @Test
    fun blockedRefreshDoesNotDelayTheLeaseOnReactivation() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.RESUMED)
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        val release = CompletableDeferred<Unit>()
        var leases = 0
        var refreshes = 0
        locker.start()
        lifecycle.withDesktopForegroundServices(
            keepAlive = {
                leases++
                try { locker.keepAlive() } finally { leases-- }
            },
            refreshStartup = {
                refreshes++
                // A native status call ignores cancellation until it returns.
                withContext(NonCancellable) { release.await() }
            },
        ).launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, leases)
        lifecycle.value = LeLifecycleState.STARTED
        runCurrent()
        assertEquals(0, leases)
        advanceTimeBy(500)
        lifecycle.value = LeLifecycleState.RESUMED
        runCurrent()
        // The refresh from the previous foreground is still blocked.
        assertEquals(1, leases)
        assertEquals(1, refreshes)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(emptyList(), locks)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, refreshes)
        assertEquals(1, leases)
    }

    private fun TestScope.locker(locks: MutableList<LockReason>) = VaultSessionLocker(
        getVaultLockAfterTimeout = object : GetVaultLockAfterTimeout {
            override fun invoke() = flowOf(10.seconds)
        },
        clearVaultSession = object : ClearVaultSession {
            override fun invoke(reason: LockReason, text: TextHolder): IO<Unit> = { locks += reason }
        },
        scope = backgroundScope,
    )
}
