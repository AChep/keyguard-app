package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingKeepScreenOnProvider(
    koinScope: Scope,
) = settingKeepScreenOnProvider(
    getKeepScreenOn = koinScope.get(),
    putKeepScreenOn = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
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
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_keep_screen_on_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
