package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import com.artemchep.keyguard.common.usecase.PutThemeExpressive
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingThemeExpressiveProvider(
    directDI: DirectDI,
) = settingThemeExpressiveProvider(
    getThemeExpressive = directDI.instance(),
    putThemeExpressive = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingThemeExpressiveProvider(
    getThemeExpressive: GetThemeExpressive,
    putThemeExpressive: PutThemeExpressive,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getThemeExpressive().map { expressive ->
    // Watch has its own design not affected by the expressive
    // theme switch.
    if (CurrentPlatform.hasWatch()) {
        return@map null
    }

    val onCheckedChange = { shouldExpressive: Boolean ->
        putThemeExpressive(shouldExpressive)
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
                "expressive",
                "material",
            ),
        ),
    ) {
        SettingThemeExpressive(
            checked = expressive,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingThemeExpressive(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Rounded.Diamond,
        title = stringResource(Res.string.pref_item_color_scheme_expressive_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
