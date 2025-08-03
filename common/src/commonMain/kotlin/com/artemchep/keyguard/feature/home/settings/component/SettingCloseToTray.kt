package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingCloseToTrayProvider(
    directDI: DirectDI,
) = settingCloseToTrayProvider(
    getCloseToTray = directDI.instance(),
    putCloseToTray = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
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
    FlatItemSimpleExpressive(
        leading = icon<RowScope>(Icons.Outlined.CloseFullscreen),
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                Switch(
                    checked = checked,
                    enabled = onCheckedChange != null,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_close_to_tray_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
