package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.usecase.GetTheme
import com.artemchep.keyguard.common.usecase.GetThemeVariants
import com.artemchep.keyguard.common.usecase.PutTheme
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingColorSchemeProvider(
    directDI: DirectDI,
) = settingColorSchemeProvider(
    getTheme = directDI.instance(),
    getThemeVariants = directDI.instance(),
    putTheme = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingColorSchemeProvider(
    getTheme: GetTheme,
    getThemeVariants: GetThemeVariants,
    putTheme: PutTheme,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getTheme(),
    getThemeVariants(),
) { theme, variants ->
    val text = getAppThemeTitle(theme, context)
    val dropdown = variants
        .map { themeVariant ->
            val actionSelected = theme == themeVariant
            val actionTitle = getAppThemeTitle(themeVariant, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putTheme(themeVariant)
                        .launchIn(windowCoroutineScope)
                },
            )
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
            ),
        ),
    ) {
        SettingFont(
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getAppThemeTitle(appTheme: AppTheme?, context: LeContext) = when (appTheme) {
    null -> textResource(Res.string.follow_system_settings, context)
    AppTheme.DARK -> textResource(Res.string.theme_dark, context)
    AppTheme.LIGHT -> textResource(Res.string.theme_light, context)
}

@Composable
private fun SettingFont(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdownSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.ColorLens),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_color_scheme_title),
                    )
                },
                text = {
                    Text(text)
                },
            )
        },
        dropdown = dropdown,
    )
}
