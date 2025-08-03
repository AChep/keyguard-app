package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorFill
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
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.composable
import com.artemchep.keyguard.ui.icons.icon
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
    val text = getAppColorsTitle(colors, context)
    val dropdown = variants
        .map { colorsVariant ->
            val actionSelected = colorsVariant == colors
            val actionTitle = getAppColorsTitle(colorsVariant, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putColors(colorsVariant)
                        .launchIn(windowCoroutineScope)
                },
                trailing = if (colorsVariant != null) {
                    composable {
                        val accentColor = Color(colorsVariant.color)
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
    FlatDropdownSimpleExpressive(
        leading = icon<RowScope>(Icons.Outlined.FormatColorFill),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_color_accent_title),
                    )
                },
                text = {
                    Text(text)
                },
            )
        },
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
