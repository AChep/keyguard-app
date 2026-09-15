package com.artemchep.keyguard.common.service.session

import com.artemchep.autotype.DesktopPowerEvent
import com.artemchep.autotype.DesktopPowerRegistration
import com.artemchep.autotype.DesktopPowerRegistrationFailureReason
import com.artemchep.autotype.DesktopPowerRegistrationResult
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VaultPowerLockServiceTest {
    @Test
    fun `one application registration survives preference and session changes and is disposed`() = runTest {
        val fixture = Fixture()
        val job = backgroundScope.launch { fixture.service().run() }
        fixture.ready.await()
        assertEquals(1, fixture.registrations)

        fixture.unlock()
        fixture.callback!!(DesktopPowerEvent.DisplaySleep)
        assertEquals(1, fixture.locks)

        fixture.enabled.value = false
        runCurrent()
        fixture.unlock()
        fixture.callback!!(DesktopPowerEvent.SystemSleep)
        assertEquals(1, fixture.locks)

        fixture.enabled.value = true
        runCurrent()
        fixture.callback!!(DesktopPowerEvent.SystemSleep)
        assertEquals(2, fixture.locks)
        assertEquals(1, fixture.registrations)
        assertFalse(fixture.removed)

        job.cancelAndJoin()
        assertTrue(fixture.removed)
        fixture.unlock()
        fixture.callback!!(DesktopPowerEvent.SystemSleep)
        assertEquals(2, fixture.locks)
    }

    @Test
    fun `registration failure shows one error and does not crash the service`() = runTest {
        val fixture = Fixture().apply { failRegistration = true }
        val job = backgroundScope.launch { fixture.service().run() }
        fixture.messageReady.await()
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(1, fixture.messages.size)
        assertEquals(ToastMessage.Type.ERROR, fixture.messages.single().type)
        fixture.enabled.value = false
        runCurrent()
        assertEquals(1, fixture.messages.size)
        job.cancelAndJoin()
    }

    @Test
    fun `other desktop platforms do not register or show an error`() = runTest {
        val fixture = Fixture()
        fixture.service(Platform.Desktop.Windows).run()
        fixture.service(Platform.Desktop.Linux.native).run()
        assertEquals(0, fixture.registrations)
        assertTrue(fixture.messages.isEmpty())
    }

    private class Fixture {
        val enabled = MutableStateFlow(true)
        val repository = SessionRepositoryImpl()
        val ready = CompletableDeferred<Unit>()
        val messageReady = CompletableDeferred<Unit>()
        val messages = mutableListOf<ToastMessage>()
        var callback: ((DesktopPowerEvent) -> Unit)? = null
        var registrations = 0
        var locks = 0
        var removed = false
        var failRegistration = false

        fun service(platform: Platform = Platform.Desktop.MacOS.Jvm) = VaultPowerLockService(
            getVaultLockAfterScreenOff = object : GetVaultLockAfterScreenOff {
                override fun invoke() = enabled
            },
            sessionRepository = repository,
            clearVaultSession = object : ClearVaultSession {
                override fun invoke(type: LockReason, reason: TextHolder): IO<Unit> = ioEffect {
                    locks++
                    repository.put(MasterSession.Empty())
                }
            },
            showMessage = object : ShowMessage {
                override fun copy(value: ToastMessage, target: String?) {
                    messages += value
                    messageReady.complete(Unit)
                }
            },
            platform = platform,
            register = { onEvent, _ ->
                registrations++
                callback = onEvent
                ready.complete(Unit)
                if (failRegistration) {
                    DesktopPowerRegistrationResult.Failure(DesktopPowerRegistrationFailureReason.InternalError)
                } else {
                    DesktopPowerRegistrationResult.Success(object : DesktopPowerRegistration {
                        override fun unregister(): Boolean {
                            removed = true
                            return true
                        }
                    })
                }
            },
        )

        fun unlock() {
            repository.put(testMasterSessionKey())
        }
    }
}
