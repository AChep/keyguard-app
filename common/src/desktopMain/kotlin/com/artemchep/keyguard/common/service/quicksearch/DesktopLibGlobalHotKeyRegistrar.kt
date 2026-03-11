package com.artemchep.keyguard.common.service.quicksearch

import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeyRegistrationFailureReason
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.autotype.registerGlobalHotKey
import arrow.core.throwIfFatal
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog

class DesktopLibGlobalHotKeyRegistrar : GlobalHotKeyRegistrar {
    override fun register(
        hotKey: GlobalHotKeySpec,
        onPressed: () -> Unit,
    ): GlobalHotKeyRegistrationResult = try {
        when (val result = registerGlobalHotKey(hotKey) {
            onPressed()
        }) {
            is GlobalHotKeyRegistrationResult.Success -> result
            is GlobalHotKeyRegistrationResult.Failure -> {
                recordLog("Quick search global hotkey registration failed: ${result.reason}.")
                result
            }
        }
    } catch (e: Throwable) {
        e.throwIfFatal()
        recordLog("Quick search global hotkey registration failed: ${GlobalHotKeyRegistrationFailureReason.InternalError}.")
        recordException(e)
        GlobalHotKeyRegistrationResult.Failure(GlobalHotKeyRegistrationFailureReason.InternalError)
    }
}
