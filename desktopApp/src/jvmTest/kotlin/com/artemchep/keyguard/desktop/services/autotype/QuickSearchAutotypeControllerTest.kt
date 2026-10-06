package com.artemchep.keyguard.desktop.services.autotype

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.autotype.AutotypeLogin
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class QuickSearchAutotypeControllerTest {
    @Test
    fun `hides popup before typing and never queues repeated shortcuts`() = runTest {
        val fixture = Fixture(backgroundScope)
        val hidden = CompletableDeferred<Unit>()
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { hidden.await(); true }
        fixture.controller.start({ fixture.login }, fixture.session.id) { error("Repeated request") }
        runCurrent()
        assertEquals(0, fixture.calls)
        hidden.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.calls)
        assertTrue(fixture.active!!())
    }

    @Test
    fun `vault retirement cancels the native lifetime callback immediately`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { true }
        runCurrent()
        fixture.session.retire()
        assertFalse(fixture.active!!())
    }

    @Test
    fun `new popup invocation invalidates previous native operation`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { true }
        runCurrent()
        fixture.controller.captureTarget()
        assertFalse(fixture.active!!())
    }

    @Test
    fun `native interruption leaves popup hidden without invoking recovery`() = runTest {
        val fixture = Fixture(backgroundScope)
        var hidden = false
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { hidden = true; true }
        runCurrent()
        assertTrue(hidden)
        assertEquals(1, fixture.calls)
        fixture.result.complete(AutotypeResult.Interrupted)
        runCurrent()
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `native input failure still invokes recovery`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { true }
        runCurrent()
        fixture.result.complete(AutotypeResult.InputFailed)
        runCurrent()
        assertEquals(AutotypeResult.InputFailed, fixture.failures.single())
    }

    @Test
    fun `stale screen session cannot type into a new vault session`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, "retired-session") { error("Must not hide") }
        runCurrent()
        assertEquals(0, fixture.calls)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `permission denial keeps popup and credentials local`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.permission = false
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { error("Must not hide") }
        runCurrent()
        assertEquals(0, fixture.calls)
        assertEquals(AutotypeResult.PermissionRequired, fixture.failures.single())
    }

    @Test
    fun `missing destination never hides the popup or starts typing`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.start({ fixture.login }, fixture.session.id) { error("Must not hide") }
        runCurrent()
        assertEquals(0, fixture.calls)
        assertEquals(AutotypeResult.Unavailable, fixture.failures.single())
    }

    @Test
    fun `old native failure cannot reopen a newer popup`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { true }
        runCurrent()
        fixture.controller.captureTarget()
        fixture.result.complete(AutotypeResult.InputFailed)
        runCurrent()
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `popup hide timeout never starts typing`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ fixture.login }, fixture.session.id) { false }
        runCurrent()
        assertEquals(0, fixture.calls)
        assertEquals(AutotypeResult.Unavailable, fixture.failures.single())
    }

    @Test
    fun `delayed code generation cannot hide or type into a newer popup`() = runTest {
        val fixture = Fixture(backgroundScope)
        val payload = CompletableDeferred<AutotypeLogin>()
        fixture.controller.captureTarget()
        fixture.controller.start({ payload.await() }, fixture.session.id) { error("Must not hide a newer popup") }
        runCurrent()
        fixture.controller.captureTarget()
        payload.complete(AutotypeLogin("", "123456"))
        runCurrent()
        assertEquals(0, fixture.calls)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `locking during code generation prevents typing`() = runTest {
        val fixture = Fixture(backgroundScope)
        val payload = CompletableDeferred<AutotypeLogin>()
        fixture.controller.captureTarget()
        fixture.controller.start({ payload.await() }, fixture.session.id) { error("Must not hide") }
        runCurrent()
        fixture.session.retire()
        payload.complete(AutotypeLogin("", "123456"))
        runCurrent()
        assertEquals(0, fixture.calls)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `missing payload reports failure without hiding the popup`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.captureTarget()
        fixture.controller.start({ null }, fixture.session.id) { error("Must not hide") }
        runCurrent()
        assertEquals(0, fixture.calls)
        assertEquals(AutotypeResult.InputFailed, fixture.failures.single())
    }

    @Test
    fun `repeated request cannot regenerate a pending code`() = runTest {
        val fixture = Fixture(backgroundScope)
        val payload = CompletableDeferred<AutotypeLogin>()
        fixture.controller.captureTarget()
        fixture.controller.start({ payload.await() }, fixture.session.id) { true }
        runCurrent()
        fixture.controller.start({ error("Must not resolve repeated request") }, fixture.session.id) { true }
        payload.complete(AutotypeLogin("", "123456"))
        runCurrent()
        assertEquals(1, fixture.calls)
        assertTrue(fixture.failures.isEmpty())
    }

    private class Session : VaultSession {
        override val id = "session"
        override val active = MutableStateFlow(true)
        override fun retire() { active.value = false }
        override fun close() = retire()
    }

    private class Fixture(scope: CoroutineScope) {
        val session = Session()
        val login = AutotypeLogin("test-user", "test-password")
        var permission = true
        var calls = 0
        var active: (() -> Boolean)? = null
        val result = CompletableDeferred<AutotypeResult>()
        val failures = mutableListOf<AutotypeResult>()
        val controller = QuickSearchAutotypeController(
            scope = scope,
            getVaultSession = object : GetVaultSession {
                private val sessions = MutableStateFlow<MasterSession>(MasterSession.Key(
                    masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)),
                    session = session,
                    origin = MasterSession.Key.Authenticated,
                    createdAt = Instant.fromEpochMilliseconds(0),
                ))
                override val valueOrNull get() = sessions.value
                override fun invoke() = sessions
            },
            service = object : AutotypeService {
                override fun captureTarget(): Long = 1
                override suspend fun requestPermission() = permission
                override fun typeLogin(
                    target: Long,
                    login: AutotypeLogin,
                    isActive: () -> Boolean,
                ): IO<AutotypeResult> = {
                    calls += 1
                    active = isActive
                    result.await()
                }
            },
            onFailure = { result -> failures += result },
        )
    }
}
