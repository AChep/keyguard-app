package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AppSceneLifecycleTest {
    @Test
    fun biometricSheetDoesNotRefreshOrRestartUnlock() {
        var refreshes = 0
        val lifecycle = AppSceneLifecycle(KeyguardScenePhase.ACTIVE) { refreshes++ }
        lifecycle.setPhase(KeyguardScenePhase.INACTIVE)
        assertEquals(LeLifecycleState.STARTED, lifecycle.state.value)
        lifecycle.setPhase(KeyguardScenePhase.ACTIVE)
        assertEquals(0, refreshes)
        lifecycle.setPhase(KeyguardScenePhase.BACKGROUND)
        lifecycle.setPhase(KeyguardScenePhase.INACTIVE)
        lifecycle.setPhase(KeyguardScenePhase.ACTIVE)
        lifecycle.setPhase(KeyguardScenePhase.ACTIVE)
        assertEquals(1, refreshes)
    }

    @Test
    fun headlessStartupAndWindowTransitionsDriveTheRealLockTimer() = runTest {
        val locks = mutableListOf<LockReason>()
        val lifecycle = AppSceneLifecycle(KeyguardScenePhase.BACKGROUND) {}
        val locker = VaultSessionLocker(
            getVaultLockAfterTimeout = object : GetVaultLockAfterTimeout {
                override fun invoke() = flowOf(10.seconds)
            },
            clearVaultSession = object : ClearVaultSession {
                override fun invoke(reason: LockReason, text: TextHolder): IO<Unit> = { locks += reason }
            },
            scope = backgroundScope,
        )
        val lifetime = startAppleSessionLifetime(backgroundScope, lifecycle.state, locker)
        runCurrent()
        assertEquals(LeLifecycleState.CREATED, lifecycle.state.value)
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
        locks.clear()

        lifecycle.setPhase(KeyguardScenePhase.ACTIVE)
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)
        lifecycle.setPhase(KeyguardScenePhase.INACTIVE)
        runCurrent()
        advanceTimeBy(500)
        lifecycle.setPhase(KeyguardScenePhase.ACTIVE)
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)

        lifetime.cancelAndJoin()
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun foregroundStartupDoesNotLockBeforeTheFirstWindowLeaves() = runTest {
        val locks = mutableListOf<LockReason>()
        // A user launch that shows its main window; the window has not opened yet.
        val lifecycle = AppSceneLifecycle(KeyguardScenePhase.ACTIVE) {}
        val locker = VaultSessionLocker(
            getVaultLockAfterTimeout = object : GetVaultLockAfterTimeout {
                override fun invoke() = flowOf(0.seconds)
            },
            clearVaultSession = object : ClearVaultSession {
                override fun invoke(reason: LockReason, text: TextHolder): IO<Unit> = { locks += reason }
            },
            scope = backgroundScope,
        )
        startAppleSessionLifetime(backgroundScope, lifecycle.state, locker)
        runCurrent()
        // A slow cold start: well past the locker's debounce.
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(emptyList(), locks)

        lifecycle.setPhase(KeyguardScenePhase.BACKGROUND)
        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }
}
