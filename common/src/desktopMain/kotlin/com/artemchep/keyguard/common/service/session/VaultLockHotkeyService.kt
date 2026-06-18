package com.artemchep.keyguard.common.service.session

import com.artemchep.autotype.GlobalHotKeyKey
import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.service.quicksearch.GlobalHotKeyRegistrar
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.lock_reason_manually
import kotlinx.coroutines.CoroutineScope

class VaultLockHotkeyService(
    private val clearVaultSession: ClearVaultSession,
    private val globalHotKeyRegistrar: GlobalHotKeyRegistrar,
    private val scope: CoroutineScope,
    private val platform: Platform = CurrentPlatform,
) {
    companion object {
        internal fun vaultLockHotKey(platform: Platform): GlobalHotKeySpec =
            when (platform) {
                is Platform.Desktop.MacOS -> GlobalHotKeySpec(
                    key = GlobalHotKeyKey.L,
                    isShiftPressed = true,
                    isMetaPressed = true,
                )

                else -> GlobalHotKeySpec(
                    key = GlobalHotKeyKey.L,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                )
            }
    }

    fun start(): () -> Unit {
        val hotKey = vaultLockHotKey(platform)
        val result = globalHotKeyRegistrar.register(
            hotKey = hotKey,
            onPressed = {
                val reason = TextHolder.Res(Res.string.lock_reason_manually)
                clearVaultSession(LockReason.LOCK, reason)
                    .launchIn(scope)
            },
        )
        val registration = when (result) {
            is GlobalHotKeyRegistrationResult.Success -> result.registration
            is GlobalHotKeyRegistrationResult.Failure -> null
        }
        return {
            registration?.close()
        }
    }
}
