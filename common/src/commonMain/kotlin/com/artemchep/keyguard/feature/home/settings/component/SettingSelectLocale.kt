package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Locale

fun settingSelectLocaleProvider(
    directDI: DirectDI,
) = settingSelectLocaleProvider(
    getLocale = directDI.instance(),
    getLocaleVariants = directDI.instance(),
    putLocale = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingSelectLocaleProvider(
    getLocale: GetLocale,
    getLocaleVariants: GetLocaleVariants,
    putLocale: PutLocale,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getLocale(),
    getLocaleVariants(),
) { locale, variants ->
    val text = getLocaleTitle(locale, context)
    val dropdown = variants
        .map { localeVariant ->
            val title = getLocaleTitle(localeVariant, context)
            LocaleItem(
                locale = localeVariant,
                title = title,
            )
        }
        .sortedWith(
            compareBy(
                { it.locale != null },
                { it.title },
            ),
        )
        .map { item ->
            val actionSelected = locale == item.locale
            FlatItemAction(
                // leading = if (item.locale != null) null else icon(Icons.Outlined.AutoAwesome),
                title = TextHolder.Value(item.title),
                selected = actionSelected,
                onClick = {
                    putLocale(item.locale)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        search = SettingIi.Search(
            group = "locale",
            tokens = listOf(
                "locale",
                "language",
                "translate",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        val hasBrowser = CurrentPlatform
            .hasBrowser()
        SettingLocale(
            text = text,
            dropdown = dropdown,
            onHelpTranslate = if (hasBrowser) {
                // lambda
                {
                    val intent = NavigationIntent.NavigateToBrowser(
                        url = "https://crowdin.com/project/keyguard",
                    )
                    navigationController.queue(intent)
                }
            } else {
                null
            },
        )
    }
}

private data class LocaleItem(
    val locale: String?,
    val title: String,
)

private suspend fun getLocaleTitle(locale: String?, context: LeContext) = locale
    ?.let {
        Locale.forLanguageTag(it).let { locale ->
            locale.getDisplayName(locale)
                .capitalize(locale)
        }
    }
    ?: textResource(Res.string.follow_system_settings, context)

@Composable
private fun SettingLocale(
    text: String,
    dropdown: List<FlatItemAction>,
    onHelpTranslate: (() -> Unit)? = null,
) {
    LocalSettingPaneComponents.current.KgPicker(
        icon = Icons.Outlined.Language,
        title = stringResource(Res.string.pref_item_locale_title),
        text = text,
        footer = if (onHelpTranslate != null) {
            // composable
            {
                TextButton(
                    modifier = Modifier
                        .padding(
                            horizontal = getSettingsButtonStartPadding(),
                            vertical = 4.dp,
                        ),
                    onClick = {
                        onHelpTranslate()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.pref_item_locale_help_translation_button),
                    )
                }
            }
        } else {
            null
        },
        dropdown = dropdown,
    )
}
