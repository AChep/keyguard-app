package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.LaunchAtLoginBridgeRegistry
import com.artemchep.keyguard.LaunchAtLoginStatus
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** macOS only. The shared use cases delegate to the Swift SMAppService bridge registered at startup. */
internal class LaunchAtLoginController(
    private val ctx: CoreContext,
) {
    private val getLaunchAtLogin: GetLaunchAtLogin by lazy { ctx.koin.get() }
    private val putLaunchAtLogin: PutLaunchAtLogin by lazy { ctx.koin.get() }

    fun observeLaunchAtLogin(
        onChange: (LaunchAtLoginSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = ctx.scope.launch {
            getLaunchAtLogin().collect { enabled ->
                val status = LaunchAtLoginBridgeRegistry.bridge?.status()
                onChange(
                    LaunchAtLoginSnapshot(
                        enabled = enabled,
                        requiresApproval = status == LaunchAtLoginStatus.REQUIRES_APPROVAL,
                        available = status != null && status != LaunchAtLoginStatus.UNAVAILABLE,
                    ),
                )
            }
        }
        return KeyguardCancellable(job)
    }

    fun setLaunchAtLogin(enabled: Boolean) {
        putLaunchAtLogin(enabled).launchIn(ctx.scope)
    }

    fun openLoginItemsSettings() {
        LaunchAtLoginBridgeRegistry.bridge?.openLoginItemsSettings()
    }
}
