package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.model.LockReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionLockerTest {
    @Test
    fun `headless startup locks without a prior keep alive and start does not reset the timer`() = runTest {
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        locker.start()
        runCurrent()

        advanceTimeBy(5.seconds)
        locker.start()
        advanceTimeBy(6.seconds)
        assertEquals(emptyList(), locks)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun `foreground activity cancels the startup timer and release begins a full timeout`() = runTest {
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        locker.start()
        runCurrent()
        advanceTimeBy(5.seconds)
        val foreground = backgroundScope.launch { locker.keepAlive() }
        runCurrent()

        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)
        foreground.cancelAndJoin()
        runCurrent()
        advanceTimeBy(11.seconds)
        assertEquals(emptyList(), locks)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun `overlapping windows and background work keep the vault alive until all release`() = runTest {
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks)
        val first = backgroundScope.launch { locker.keepAlive() }
        val second = backgroundScope.launch { locker.keepAlive() }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), locks)

        second.cancelAndJoin()
        runCurrent()
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    @Test
    fun `immediate locking still allows window transitions during the debounce`() = runTest {
        val locks = mutableListOf<LockReason>()
        val locker = locker(locks, Duration.ZERO)
        locker.start()
        runCurrent()
        advanceTimeBy(500)
        val foreground = backgroundScope.launch { locker.keepAlive() }
        runCurrent()
        advanceTimeBy(2.seconds)
        assertEquals(emptyList(), locks)

        foreground.cancelAndJoin()
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(LockReason.TIMEOUT), locks)
    }

    private fun TestScope.locker(
        locks: MutableList<LockReason>,
        timeout: Duration = 10.seconds,
    ) = testVaultSessionLocker(backgroundScope, timeout) { locks += it }
}
