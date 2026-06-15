package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.Stub
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingThemeUseAmoledDarkProvider(
    directDI: DirectDI,
) = settingThemeUseAmoledDarkProvider(
    getThemeUseAmoledDark = directDI.instance(),
    putThemeUseAmoledDark = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingThemeUseAmoledDarkProvider(
    getThemeUseAmoledDark: GetThemeUseAmoledDark,
    putThemeUseAmoledDark: PutThemeUseAmoledDark,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getThemeUseAmoledDark().map { useAmoledDark ->
    if (CurrentPlatform.hasWatch()) {
        return@map null
    }

    val onCheckedChange = { shouldUseAmoledDark: Boolean ->
        putThemeUseAmoledDark(shouldUseAmoledDark)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "color_scheme",
            tokens = listOf(
                "schema",
                "color",
                "theme",
                "dark",
                "light",
                "black",
            ),
        ),
    ) {
        SettingThemeUseAmoledDark(
            checked = useAmoledDark,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingThemeUseAmoledDark(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Stub,
        title = stringResource(Res.string.pref_item_color_scheme_amoled_dark_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
