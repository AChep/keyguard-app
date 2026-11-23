package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import com.artemchep.keyguard.common.usecase.GetStartInBackground
import com.artemchep.keyguard.common.usecase.PutStartInBackground
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingStartInBackgroundProvider(
    koinScope: Scope,
): SettingComponent {
    // Only the desktop binds the launch-at-login use cases.
    if (CurrentPlatform !is Platform.Desktop) return flowOf(null)
    return settingStartInBackgroundProvider(
        getLaunchAtLogin = koinScope.get(),
        getStartInBackground = koinScope.get(),
        putStartInBackground = koinScope.get(),
        windowCoroutineScope = koinScope.get(),
    )
}

fun settingStartInBackgroundProvider(
    getLaunchAtLogin: GetLaunchAtLogin,
    getStartInBackground: GetStartInBackground,
    putStartInBackground: PutStartInBackground,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getLaunchAtLogin(),
    getStartInBackground(),
) { launchAtLogin, startInBackground ->
    // The preference only applies to a launch at login.
    if (launchAtLogin.status?.isEnabled != true) return@combine null

    val onCheckedChange = { shouldStartInBackground: Boolean ->
        putStartInBackground(shouldStartInBackground)
            .launchIn(windowCoroutineScope)
        Unit
    }
    SettingIi(
        search = SettingIi.Search(
            group = "ux",
            tokens = listOf(
                "start",
                "background",
                "login",
            ),
        ),
    ) {
        SettingStartInBackground(
            checked = startInBackground,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingStartInBackground(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_start_in_background_title),
        text = stringResource(Res.string.pref_item_start_in_background_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
