package com.artemchep.keyguard.common.service.session

import com.artemchep.autotype.GlobalHotKeyKey
import com.artemchep.autotype.GlobalHotKeyRegistration
import com.artemchep.autotype.GlobalHotKeyRegistrationFailureReason
import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.service.quicksearch.GlobalHotKeyRegistrar
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.lock_reason_manually
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class VaultLockHotkeyServiceTest {
    @Test
    fun `start registers macos vault lock shortcut and disposes registration`() = runTest {
        var registeredHotKey: GlobalHotKeySpec? = null
        var callback: (() -> Unit)? = null
        var disposed = false

        val service = VaultLockHotkeyService(
            clearVaultSession = RecordingClearVaultSession(),
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { hotKey, onPressed ->
                registeredHotKey = hotKey
                callback = onPressed
                GlobalHotKeyRegistrationResult.Success(
                    object : GlobalHotKeyRegistration {
                        override fun unregister(): Boolean {
                            disposed = true
                            return true
                        }
                    },
                )
            },
            scope = this,
            platform = Platform.Desktop.MacOS,
        )

        val stop = service.start()

        assertEquals(VaultLockHotkeyService.vaultLockHotKey(Platform.Desktop.MacOS), registeredHotKey)
        assertNotNull(callback)

        stop()
        assertTrue(disposed)
    }

    @Test
    fun `start locks vault manually when hotkey callback is invoked`() = runTest {
        var callback: (() -> Unit)? = null
        val clearVaultSession = RecordingClearVaultSession()
        val service = VaultLockHotkeyService(
            clearVaultSession = clearVaultSession,
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { _, onPressed ->
                callback = onPressed
                GlobalHotKeyRegistrationResult.Success(
                    object : GlobalHotKeyRegistration {
                        override fun unregister(): Boolean = true
                    },
                )
            },
            scope = this,
            platform = Platform.Desktop.Windows,
        )

        service.start()
        assertNotNull(callback)

        callback.invoke()

        assertEquals(
            LockCall(
                reason = LockReason.LOCK,
                message = TextHolder.Res(Res.string.lock_reason_manually),
            ),
            clearVaultSession.call.await(),
        )
    }

    @Test
    fun `start handles missing registration`() = runTest {
        var registerCalls = 0
        val service = VaultLockHotkeyService(
            clearVaultSession = RecordingClearVaultSession(),
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { _, _ ->
                registerCalls += 1
                GlobalHotKeyRegistrationResult.Failure(
                    GlobalHotKeyRegistrationFailureReason.Unavailable,
                )
            },
            scope = this,
            platform = Platform.Desktop.Linux.native,
        )

        val stop = service.start()

        stop()
        assertEquals(1, registerCalls)
    }

    @Test
    fun `vault lock hotkey matches platform defaults`() {
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.L,
                isShiftPressed = true,
                isMetaPressed = true,
            ),
            VaultLockHotkeyService.vaultLockHotKey(Platform.Desktop.MacOS),
        )
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.L,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
            VaultLockHotkeyService.vaultLockHotKey(Platform.Desktop.Windows),
        )
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.L,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
            VaultLockHotkeyService.vaultLockHotKey(Platform.Desktop.Linux.native),
        )
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.L,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
            VaultLockHotkeyService.vaultLockHotKey(Platform.Desktop.Other),
        )
    }

    private data class LockCall(
        val reason: LockReason,
        val message: TextHolder,
    )

    private class RecordingClearVaultSession : ClearVaultSession {
        val call = CompletableDeferred<LockCall>()

        override fun invoke(
            reason: LockReason,
            message: TextHolder,
        ): IO<Unit> = ioEffect {
            call.complete(LockCall(reason, message))
        }
    }
}
