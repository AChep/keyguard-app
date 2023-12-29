package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
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
    FlatItem(
        leading = icon<RowScope>(Icons.Stub),
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_color_scheme_amoled_dark_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
