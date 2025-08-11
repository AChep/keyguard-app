package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import com.artemchep.keyguard.common.usecase.PutThemeExpressive
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.icon
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
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Rounded.Diamond),
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
                text = stringResource(Res.string.pref_item_color_scheme_expressive_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
