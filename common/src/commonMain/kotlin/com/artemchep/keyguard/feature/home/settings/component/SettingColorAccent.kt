package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.usecase.GetColors
import com.artemchep.keyguard.common.usecase.GetColorsVariants
import com.artemchep.keyguard.common.usecase.PutColors
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.composable
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.appColorScheme
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingColorAccentProvider(
    directDI: DirectDI,
) = settingColorAccentProvider(
    getColors = directDI.instance(),
    getColorsVariants = directDI.instance(),
    putColors = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingColorAccentProvider(
    getColors: GetColors,
    getColorsVariants: GetColorsVariants,
    putColors: PutColors,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getColors(),
    getColorsVariants(),
) { colors, variants ->
    if (CurrentPlatform.hasWatch()) {
        return@combine null
    }

    val text = getAppColorsTitle(colors, context)
    val dropdown = variants
        .map { colorsVariant ->
            val actionSelected = colorsVariant == colors
            val actionTitle = getAppColorsTitle(colorsVariant, context)
            FlatItemAction(
                id = "settings.colorAccent.${colorsVariant?.key ?: "default"}",
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putColors(colorsVariant)
                        .launchIn(windowCoroutineScope)
                },
                trailing = if (colorsVariant != null) {
                    composable {
                        // For an accurate re-presentation of the color we create a
                        // proper color scheme object the same way we do it for the
                        // root keyguard theme.
                        val materialColorScheme = appColorScheme(
                            colors = colorsVariant,
                            isDarkColorScheme = MaterialTheme.colorScheme.isDark,
                        )

                        val accentColor = materialColorScheme.primary
                        Box(
                            Modifier
                                .background(accentColor, CircleShape)
                                .size(24.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }

    SettingIi(
        search = SettingIi.Search(
            group = "color_scheme",
            tokens = listOf(
                "schema",
                "color",
                "accent",
                "theme",
            ),
        ),
    ) {
        SettingFont(
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getAppColorsTitle(appColors: AppColors?, context: LeContext) = when (appColors) {
    null -> textResource(Res.string.follow_system_settings, context)
    else -> appColors.title
}

@Composable
private fun SettingFont(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    LocalSettingPaneComponents.current.KgPicker(
        icon = Icons.Outlined.FormatColorFill,
        title = stringResource(Res.string.pref_item_color_accent_title),
        text = text,
        trailing = {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .size(24.dp),
            )
        },
        dropdown = dropdown,
    )
}
