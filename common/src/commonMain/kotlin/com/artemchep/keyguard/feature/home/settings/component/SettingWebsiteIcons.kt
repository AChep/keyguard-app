package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingWebsiteIconsProvider(
    directDI: DirectDI,
) = settingWebsiteIconsProvider(
    getWebsiteIcons = directDI.instance(),
    putWebsiteIcons = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingWebsiteIconsProvider(
    getWebsiteIcons: GetWebsiteIcons,
    putWebsiteIcons: PutWebsiteIcons,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getWebsiteIcons().map { websiteIcons ->
    val onCheckedChange = { shouldWebsiteIcons: Boolean ->
        putWebsiteIcons(shouldWebsiteIcons)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "icon",
            tokens = listOf(
                "icon",
                "website",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        val hasBrowser = CurrentPlatform
            .hasBrowser()
        SettingMarkdown(
            checked = websiteIcons,
            onCheckedChange = onCheckedChange,
            onLearnMore = if (hasBrowser) {
                // lambda
                {
                    val intent = NavigationIntent.NavigateToBrowser(
                        url = "https://bitwarden.com/help/website-icons/",
                    )
                    navigationController.queue(intent)
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SettingMarkdown(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    onLearnMore: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.KeyguardWebsite,
        title = stringResource(Res.string.pref_item_load_website_icons_title),
        text = stringResource(Res.string.pref_item_load_website_icons_text),
        footer = if (onLearnMore != null) {
            // composable
            {
                TextButton(
                    modifier = Modifier
                        .padding(
                            horizontal = getSettingsButtonStartPadding(),
                            vertical = 4.dp,
                        ),
                    onClick = {
                        onLearnMore()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.learn_more),
                    )
                }
            }
        } else {
            null
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
