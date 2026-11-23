package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginState
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import com.artemchep.keyguard.common.usecase.OpenLaunchAtLoginSettings
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingLaunchAtLoginProvider(koinScope: Scope): SettingComponent {
    if (CurrentPlatform !is Platform.Desktop) return flowOf(null)
    val getLaunchAtLogin = koinScope.get<GetLaunchAtLogin>()
    val putLaunchAtLogin = koinScope.get<PutLaunchAtLogin>()
    val openLaunchAtLoginSettings = koinScope.get<OpenLaunchAtLoginSettings>()
    val scope = koinScope.get<WindowCoroutineScope>()
    return getLaunchAtLogin().map { state ->
        if (!state.isShown) return@map null
        SettingIi(
            search = SettingIi.Search(group = "ux", tokens = listOf("start", "login", "boot", "background")),
        ) {
            SettingLaunchAtLogin(
                state = state,
                onCheckedChange = { enabled -> putLaunchAtLogin(enabled).launchIn(scope) },
                onOpenSettings = { openLaunchAtLoginSettings().launchIn(scope) },
            )
        }
    }
}

@Composable
private fun SettingLaunchAtLogin(
    state: LaunchAtLoginState,
    onCheckedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val status = state.status
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_launch_at_login_title),
        text = state.descriptionRes()?.let { stringResource(it) },
        checked = status?.isEnabled == true,
        onCheckedChange = onCheckedChange.takeUnless { state.busy || status?.canChange != true },
        footer = {
            if (status?.opensSystemSettings == true) {
                TextButton(
                    enabled = !state.busy,
                    onClick = onOpenSettings,
                ) {
                    Text(stringResource(Res.string.pref_item_launch_at_login_open_settings))
                }
            }
        },
    )
}

/** The settings row message; shared with the Apple settings screen. */
fun LaunchAtLoginState.descriptionRes(): StringResource? = when {
    failed -> Res.string.pref_item_launch_at_login_failed_text
    else -> when (status) {
        LaunchAtLoginStatus.REQUIRES_APPROVAL -> Res.string.pref_item_launch_at_login_approval_note
        LaunchAtLoginStatus.DISABLED_BY_USER -> Res.string.pref_item_launch_at_login_disabled_text
        LaunchAtLoginStatus.UNAVAILABLE -> Res.string.pref_item_launch_at_login_unavailable_text
        LaunchAtLoginStatus.STALE -> Res.string.pref_item_launch_at_login_stale_text
        LaunchAtLoginStatus.UNKNOWN -> Res.string.pref_item_launch_at_login_state_unknown_text
        LaunchAtLoginStatus.ENABLED_BY_POLICY,
        LaunchAtLoginStatus.DISABLED_BY_POLICY -> Res.string.pref_item_launch_at_login_policy_text
        LaunchAtLoginStatus.ENABLED, LaunchAtLoginStatus.DISABLED, null -> null
    }
}
