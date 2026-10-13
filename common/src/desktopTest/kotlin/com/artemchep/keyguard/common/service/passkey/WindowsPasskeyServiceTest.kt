package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.MasterSession.Key.Persisted
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.session.testMasterSessionKey
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyEvent
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyFailure
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsPasskeyServiceTest {
    private val authenticator = testAuthenticator()

    private inner class Fixture(scope: CoroutineScope, session: MasterSession, vararg ciphers: DSecret) {
        val bridge = FakeWindowsPasskeyBridge()
        val sessions = FakeVaultSessions(session)
        val vault = FakeVault(*ciphers)
        val service = WindowsPasskeyService(
            getVaultSession = sessions,
            vaultAccess = vault.access,
            vaultSessionLocker = sessions.locker(scope),
            authenticator = authenticator,
            showMessage = object : ShowMessage {
                override fun copy(value: ToastMessage, target: String?) = Unit
            },
            bridge = bridge,
            scope = scope,
        )
    }

    @Test
    fun `a request Windows stops waiting on is cancelled`() = runTest {
        val passkey = SigningPasskey(authenticator)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred()
        fixture.service.start()
        runCurrent()
        assertEquals(listOf<Long?>(null), fixture.bridge.waits)

        fixture.bridge.events.send(WindowsPasskeyEvent(request = testSignInRequest(), activeId = 1))
        runCurrent()
        assertEquals(1, fixture.bridge.verifications.size)
        assertEquals(listOf(null, 1L), fixture.bridge.waits)

        // For example, the user closed the Windows dialog.
        fixture.bridge.ended += 1
        fixture.bridge.events.send(WindowsPasskeyEvent(request = null, activeId = null))
        runCurrent()
        // Hello cannot be dismissed: Keyguard learns that the request ended once it closes.
        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertFalse(fixture.bridge.answered(1))
        assertEquals(listOf(null, 1L, null), fixture.bridge.waits)
    }

    @Test
    fun `a request the user dismisses tells Windows so`() = runTest {
        val passkey = SigningPasskey(authenticator)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred()
        fixture.service.start()
        runCurrent()
        fixture.bridge.events.send(WindowsPasskeyEvent(request = testSignInRequest(), activeId = 1))
        runCurrent()

        fixture.service.cancel(1)()
        assertNull(fixture.bridge.answer(1))
        assertEquals(WindowsPasskeyFailure.DISMISSED, fixture.bridge.failure(1))
    }

    @Test
    fun `a server that failed to start at launch serves again`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty())
        fixture.bridge.serving = false
        fixture.service.start()
        runCurrent()
        assertEquals(1, fixture.bridge.serves)
        assertEquals(1, fixture.bridge.waits.size)

        // The retried wait receives requests again.
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, fixture.bridge.waits.size)
        fixture.bridge.events.send(WindowsPasskeyEvent(request = null, activeId = null))
        runCurrent()
        assertEquals(1, fixture.bridge.serves)
        assertEquals(3, fixture.bridge.waits.size)
    }

    @Test
    fun `an unregistered provider listens only once registered`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty())
        fixture.bridge.status = WindowsPasskeyStatus.UNREGISTERED
        fixture.service.start()
        runCurrent()
        assertTrue(fixture.bridge.waits.isEmpty())

        fixture.service.setRegistered(true)()
        runCurrent()
        assertEquals(WindowsPasskeyStatus.DISABLED, fixture.service.status.value)
        assertEquals(listOf<Long?>(null), fixture.bridge.waits)
    }

    @Test
    fun `while Windows reports status changes a long wait only covers a missed one`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty())
        fixture.service.start()
        runCurrent()
        fixture.bridge.events.send(WindowsPasskeyEvent(request = null, activeId = null, watching = true))
        runCurrent()
        fixture.bridge.events.send(WindowsPasskeyEvent(request = null, activeId = null))
        runCurrent()
        // Without status reports, the service polls the status itself.
        assertEquals(listOf<Duration?>(30.seconds, 5.minutes, 30.seconds), fixture.bridge.timeouts)
    }

    @Test
    fun `a closed bridge ends the event loop`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted))
        fixture.service.start()
        runCurrent()
        fixture.bridge.events.send(WindowsPasskeyEvent(request = null, activeId = null, closed = true))
        runCurrent()

        fixture.bridge.events.send(WindowsPasskeyEvent(request = testSignInRequest(), activeId = 1))
        runCurrent()
        assertEquals(1, fixture.bridge.waits.size)
        assertFalse(fixture.bridge.answered(1))
    }

    @Test
    fun `the index follows the vault and Windows learns when it locks`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty(), testCipher(testPasskey()))
        // Registered, but not enabled: the index does not depend on it.
        fixture.bridge.status = WindowsPasskeyStatus.DISABLED
        fixture.service.start()
        runCurrent()
        assertEquals(listOf(true), fixture.bridge.locks)

        fixture.sessions.state.value = testMasterSessionKey(Persisted)
        runCurrent()
        assertEquals(listOf(true, false), fixture.bridge.locks)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertContentEquals(testPasskey().descriptor().id, fixture.bridge.syncs.single().single().credentialId)

        fixture.sessions.state.value = MasterSession.Empty()
        runCurrent()
        assertEquals(listOf(true, false, true), fixture.bridge.locks)
    }

    @Test
    fun `a failing index update is reported and retried`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(testPasskey()))
        fixture.bridge.status = WindowsPasskeyStatus.ENABLED
        fixture.bridge.failingSyncs = 1
        fixture.service.start()
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(fixture.service.cacheFailed.value)
        assertTrue(fixture.bridge.syncs.isEmpty())

        // The first retry waits 5 seconds, give or take its jitter.
        advanceTimeBy(6.seconds)
        runCurrent()
        assertFalse(fixture.service.cacheFailed.value)
        assertEquals(1, fixture.bridge.syncs.size)
    }

    @Test
    fun `erasing data clears the index and stops refilling it`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(testPasskey()))
        fixture.service.start()
        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, fixture.bridge.syncs.size)

        fixture.service.beforeClearData()
        assertEquals(1, fixture.bridge.clears)
        fixture.sessions.state.value = MasterSession.Empty()
        fixture.vault.ciphers.value = emptyList()
        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(false), fixture.bridge.locks)
        assertEquals(1, fixture.bridge.syncs.size)
    }

    @Test
    fun `a failed erase keeps the index in sync`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(testPasskey()))
        fixture.bridge.failingClears = 1
        fixture.service.start()
        runCurrent()

        assertFailsWith<IllegalStateException> { fixture.service.beforeClearData() }
        runCurrent()
        fixture.sessions.state.value = MasterSession.Empty()
        runCurrent()
        assertTrue(fixture.bridge.locks.last())
    }
}
