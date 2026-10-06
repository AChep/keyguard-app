package com.artemchep.keyguard

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart

private val launchAtLoginEnabledFlow = MutableStateFlow(false)

private fun isEnabled(status: LaunchAtLoginStatus): Boolean = when (status) {
    // REQUIRES_APPROVAL still counts as "registered" — the user toggled it on; it
    // just needs approval in System Settings.
    LaunchAtLoginStatus.ENABLED,
    LaunchAtLoginStatus.REQUIRES_APPROVAL,
        -> true

    LaunchAtLoginStatus.DISABLED,
    LaunchAtLoginStatus.UNAVAILABLE,
        -> false
}

private fun refreshLaunchAtLogin() {
    val status = LaunchAtLoginBridgeRegistry.bridge?.status()
        ?: LaunchAtLoginStatus.UNAVAILABLE
    launchAtLoginEnabledFlow.value = isEnabled(status)
}

internal object GetLaunchAtLoginMacos : GetLaunchAtLogin {
    override fun invoke(): Flow<Boolean> = launchAtLoginEnabledFlow
        .asStateFlow()
        // Re-read the live status whenever a new collector subscribes.
        .onStart { refreshLaunchAtLogin() }
}

internal object PutLaunchAtLoginMacos : PutLaunchAtLogin {
    override fun invoke(enabled: Boolean): IO<Unit> = ioEffect {
        LaunchAtLoginBridgeRegistry.bridge?.setEnabled(enabled)
        refreshLaunchAtLogin()
    }
}
