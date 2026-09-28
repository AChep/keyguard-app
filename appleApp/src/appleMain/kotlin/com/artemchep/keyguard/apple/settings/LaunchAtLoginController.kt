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

/**
 * Launch at login (macOS). Thin bridge over the shared Get/PutLaunchAtLogin use
 * cases, which delegate to the Swift SMAppService bridge registered at startup
 * via [LaunchAtLoginBridgeRegistry].
 */
internal class LaunchAtLoginController(
    private val ctx: CoreContext,
) {
    private val getLaunchAtLogin: GetLaunchAtLogin by lazy { ctx.koin.get() }
    private val putLaunchAtLogin: PutLaunchAtLogin by lazy { ctx.koin.get() }

    /**
     * Observes whether the app launches at login. Emits a fresh snapshot whenever
     * the registration changes (e.g. after [setLaunchAtLogin]); the requires-
     * approval / available flags are read live from the registered bridge.
     */
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

    /** Registers / unregisters the app as a login item via [PutLaunchAtLogin]. */
    fun setLaunchAtLogin(enabled: Boolean) {
        putLaunchAtLogin(enabled).launchIn(ctx.scope)
    }

    /** Opens System Settings ▸ Login Items (for the requires-approval case). */
    fun openLoginItemsSettings() {
        LaunchAtLoginBridgeRegistry.bridge?.openLoginItemsSettings()
    }
}
