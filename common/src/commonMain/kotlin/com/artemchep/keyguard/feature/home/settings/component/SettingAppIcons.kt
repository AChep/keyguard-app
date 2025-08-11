package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.PutAppIcons
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAppIconsProvider(
    directDI: DirectDI,
) = settingAppIconsProvider(
    getAppIcons = directDI.instance(),
    putAppIcons = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAppIconsProvider(
    getAppIcons: GetAppIcons,
    putAppIcons: PutAppIcons,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAppIcons().map { appIcons ->
    val onCheckedChange = { shouldAppIcons: Boolean ->
        putAppIcons(shouldAppIcons)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "icon",
            tokens = listOf(
                "icon",
                "app",
            ),
        ),
    ) {
        SettingAppIcons(
            checked = appIcons,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAppIcons(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemLayoutExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Stub),
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
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_load_app_icons_title),
                    )
                },
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
