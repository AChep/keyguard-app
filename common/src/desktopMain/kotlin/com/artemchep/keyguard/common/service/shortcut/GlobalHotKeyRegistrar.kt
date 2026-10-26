package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec

fun interface GlobalHotKeyRegistrar {
    fun register(
        hotKey: GlobalHotKeySpec,
        onPressed: () -> Unit,
    ): GlobalHotKeyRegistrationResult
}
