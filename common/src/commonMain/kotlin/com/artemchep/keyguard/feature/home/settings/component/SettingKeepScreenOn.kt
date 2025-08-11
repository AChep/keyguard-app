package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingKeepScreenOnProvider(
    directDI: DirectDI,
) = settingKeepScreenOnProvider(
    getKeepScreenOn = directDI.instance(),
    putKeepScreenOn = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingKeepScreenOnProvider(
    getKeepScreenOn: GetKeepScreenOn,
    putKeepScreenOn: PutKeepScreenOn,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getKeepScreenOn().map { keepScreenOn ->
    val onCheckedChange = { shouldKeepScreenOn: Boolean ->
        putKeepScreenOn(shouldKeepScreenOn)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "ui",
            tokens = listOf(
                "screen",
                "on",
                "keep",
            ),
        ),
    ) {
        SettingKeepScreenOn(
            checked = keepScreenOn,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingKeepScreenOn(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
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
                text = stringResource(Res.string.pref_item_keep_screen_on_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
