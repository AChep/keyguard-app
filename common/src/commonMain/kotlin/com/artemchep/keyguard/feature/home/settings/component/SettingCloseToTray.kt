package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingCloseToTrayProvider(
    koinScope: Scope,
) = settingCloseToTrayProvider(
    getCloseToTray = koinScope.get(),
    putCloseToTray = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingCloseToTrayProvider(
    getCloseToTray: GetCloseToTray,
    putCloseToTray: PutCloseToTray,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getCloseToTray().map { closeToTray ->
    val onCheckedChange = { shouldCloseToTray: Boolean ->
        putCloseToTray(shouldCloseToTray)
            .launchIn(windowCoroutineScope)
        Unit
    }

    if (CurrentPlatform is Platform.Desktop) {
        SettingIi(
            search = SettingIi.Search(
                group = "ux",
                tokens = listOf(
                    "close",
                    "tray",
                    "taskbar",
                ),
            ),
        ) {
            SettingCloseToTray(
                checked = closeToTray,
                onCheckedChange = onCheckedChange,
            )
        }
    } else {
        null
    }
}

@Composable
private fun SettingCloseToTray(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.CloseFullscreen,
        title = stringResource(Res.string.pref_item_close_to_tray_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
