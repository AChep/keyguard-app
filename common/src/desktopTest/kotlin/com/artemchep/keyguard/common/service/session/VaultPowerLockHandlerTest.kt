package com.artemchep.keyguard.common.service.session

import com.artemchep.autotype.DesktopPowerEvent
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.impl.ClearVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultSessionImpl
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VaultPowerLockHandlerTest {
    @Test
    fun `both sleep triggers clear the session before returning with the correct reason`() {
        listOf(
            DesktopPowerEvent.DisplaySleep to "display off",
            DesktopPowerEvent.SystemSleep to "system sleep",
        ).forEach { (event, reason) ->
            val fixture = Fixture()
            fixture.handler.onEvent(event)
            assertIs<MasterSession.Empty>(fixture.session())
            val (type, holder) = fixture.calls.single()
            assertEquals(LockReason.TIMEOUT, type)
            assertEquals(reason, assertIs<TextHolder.Value>(holder).data)
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test
    fun `production clear use case replaces the repository session within the callback`() {
        val repository = SessionRepositoryImpl().apply { put(testMasterSessionKey()) }
        val handler = VaultPowerLockHandler(
            isEnabled = { true },
            getSession = { repository.get().first() },
            clearVaultSession = ClearVaultSessionImpl(LeContext(), PutVaultSessionImpl(repository)),
            displayReason = TextHolder.Value("display off"),
            sleepReason = TextHolder.Value("system sleep"),
            onError = { throw AssertionError(it) },
        )
        handler.onEvent(DesktopPowerEvent.SystemSleep)
        val session = assertIs<MasterSession.Empty>(runBlocking { repository.get().first() })
        assertEquals("system sleep", session.lockInfo?.reason)
        assertEquals(LockReason.TIMEOUT, session.lockInfo?.type)
    }

    @Test
    fun `disabled preference and already locked sessions are ignored`() {
        val fixture = Fixture()
        fixture.enabled = false
        DesktopPowerEvent.entries.forEach(fixture.handler::onEvent)
        assertTrue(fixture.calls.isEmpty())
        fixture.enabled = true
        fixture.repository.put(MasterSession.Empty())
        DesktopPowerEvent.entries.forEach(fixture.handler::onEvent)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `duplicate sleep and wake notifications do not relock a fresh session`() {
        val fixture = Fixture()
        fixture.handler.onEvent(DesktopPowerEvent.DisplaySleep)
        fixture.handler.onEvent(DesktopPowerEvent.SystemSleep)
        assertEquals(1, fixture.calls.size)
        val fresh = testMasterSessionKey()
        fixture.repository.put(fresh)
        fixture.handler.onEvent(DesktopPowerEvent.DisplayWake)
        fixture.handler.onEvent(DesktopPowerEvent.SystemWake)
        assertSame(fresh, fixture.session())
        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun `wake retries a failed lock only for the same session`() {
        val fixture = Fixture()
        fixture.fail = true
        fixture.handler.onEvent(DesktopPowerEvent.SystemSleep)
        assertEquals(1, fixture.errors.size)
        fixture.fail = false
        fixture.handler.onEvent(DesktopPowerEvent.SystemWake)
        assertIs<MasterSession.Empty>(fixture.session())
        assertEquals(2, fixture.calls.size)
        assertEquals(fixture.calls[0], fixture.calls[1])
        fixture.handler.onEvent(DesktopPowerEvent.DisplayWake)
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun `a failed lock is not retried against a replacement session`() {
        val fixture = Fixture()
        fixture.fail = true
        fixture.handler.onEvent(DesktopPowerEvent.DisplaySleep)
        val fresh = testMasterSessionKey()
        fixture.repository.put(fresh)
        fixture.fail = false
        fixture.handler.onEvent(DesktopPowerEvent.DisplayWake)
        fixture.handler.onEvent(DesktopPowerEvent.SystemWake)
        assertSame(fresh, fixture.session())
        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun `disabling the preference discards a pending retry`() {
        val fixture = Fixture()
        fixture.fail = true
        fixture.handler.onEvent(DesktopPowerEvent.DisplaySleep)
        fixture.enabled = false
        fixture.handler.onEvent(DesktopPowerEvent.DisplayWake)
        fixture.enabled = true
        fixture.fail = false
        fixture.handler.onEvent(DesktopPowerEvent.SystemWake)
        assertIs<MasterSession.Key>(fixture.session())
        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun `a suspended lock attempt times out and can be retried on wake`() {
        val fixture = Fixture(timeoutMillis = 30)
        fixture.suspendLock = true
        fixture.handler.onEvent(DesktopPowerEvent.SystemSleep)
        assertEquals(1, fixture.errors.size)
        fixture.suspendLock = false
        fixture.handler.onEvent(DesktopPowerEvent.SystemWake)
        assertIs<MasterSession.Empty>(fixture.session())
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun `stopping prevents subsequent callbacks from locking`() {
        val fixture = Fixture()
        fixture.handler.stop()
        DesktopPowerEvent.entries.forEach(fixture.handler::onEvent)
        assertTrue(fixture.calls.isEmpty())
    }

    private class Fixture(timeoutMillis: Long = 1_000L) {
        val repository = SessionRepositoryImpl().apply { put(testMasterSessionKey()) }
        var enabled = true
        var fail = false
        var suspendLock = false
        val calls = mutableListOf<Pair<LockReason, TextHolder>>()
        val errors = mutableListOf<Throwable>()
        val handler = VaultPowerLockHandler(
            isEnabled = { enabled },
            getSession = { repository.get().first() },
            clearVaultSession = object : ClearVaultSession {
                override fun invoke(type: LockReason, reason: TextHolder): IO<Unit> = ioEffect {
                    calls += type to reason
                    if (suspendLock) awaitCancellation()
                    if (fail) error("lock failed")
                    repository.put(MasterSession.Empty())
                }
            },
            displayReason = TextHolder.Value("display off"),
            sleepReason = TextHolder.Value("system sleep"),
            onError = { errors += it },
            timeoutMillis = timeoutMillis,
        )

        fun session() = runBlocking { repository.get().first() }
    }
}
