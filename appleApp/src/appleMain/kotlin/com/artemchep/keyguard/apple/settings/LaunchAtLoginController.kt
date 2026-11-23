package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginState
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import com.artemchep.keyguard.common.usecase.OpenLaunchAtLoginSettings
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.feature.home.settings.component.descriptionRes
import com.artemchep.keyguard.feature.localization.textResource
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** macOS only. The use cases share the OS-backed service with the startup refresh. */
internal class LaunchAtLoginController(
    private val ctx: CoreContext,
) {
    private val getLaunchAtLogin: GetLaunchAtLogin by lazy { ctx.koin.get() }
    private val putLaunchAtLogin: PutLaunchAtLogin by lazy { ctx.koin.get() }
    private val openLaunchAtLoginSettings: OpenLaunchAtLoginSettings by lazy { ctx.koin.get() }
    private val service: LaunchAtLoginService by lazy { ctx.koin.get() }

    fun observeLaunchAtLogin(
        onChange: (LaunchAtLoginSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = ctx.scope.launch {
            // Resolve the text only when the message changes, not on every busy flip.
            var messageRes: StringResource? = null
            var message: String? = null
            getLaunchAtLogin().collect { state ->
                val res = state.descriptionRes()
                if (res != messageRes) {
                    messageRes = res
                    message = res?.let { textResource(it, ctx.koin.get()) }
                }
                onChange(state.toLaunchAtLoginSnapshot(message))
            }
        }
        return KeyguardCancellable(job)
    }

    fun setLaunchAtLogin(enabled: Boolean) {
        putLaunchAtLogin(enabled).launchIn(ctx.scope)
    }

    fun openLoginItemsSettings() {
        openLaunchAtLoginSettings().launchIn(ctx.scope)
    }

    /** App activation and Retry: the Retry button only exists while the state is observed. */
    fun refresh() {
        ctx.scope.launch { service.refreshIfObserved() }
    }
}

internal fun LaunchAtLoginState.toLaunchAtLoginSnapshot(message: String?): LaunchAtLoginSnapshot =
    LaunchAtLoginSnapshot(
        enabled = status?.isEnabled == true,
        requiresApproval = status == LaunchAtLoginStatus.REQUIRES_APPROVAL,
        // Not read yet, or the first read failed.
        available = status?.canChange == true,
        shown = isShown,
        busy = busy,
        failed = failed,
        canOpenSettings = status?.opensSystemSettings == true,
        message = message,
    )
