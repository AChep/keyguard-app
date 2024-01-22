package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource
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
        SettingMarkdown(
            checked = websiteIcons,
            onCheckedChange = onCheckedChange,
            onLearnMore = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://bitwarden.com/help/website-icons/",
                )
                navigationController.queue(intent)
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
    Column {
        FlatItemLayout(
            leading = icon<RowScope>(Icons.Outlined.KeyguardWebsite),
            trailing = {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentEnforcement provides false,
                ) {
                    Switch(
                        checked = checked,
                        enabled = onCheckedChange != null,
                        onCheckedChange = onCheckedChange,
                    )
                }
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.strings.pref_item_load_website_icons_title),
                        )
                    },
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.bodySmall,
                    text = stringResource(Res.strings.pref_item_load_website_icons_text),
                )
            },
            onClick = onCheckedChange?.partially1(!checked),
        )
        TextButton(
            modifier = Modifier
                .padding(horizontal = 46.dp),
            enabled = onLearnMore != null,
            onClick = {
                onLearnMore?.invoke()
            },
        ) {
            Text(
                text = stringResource(Res.strings.learn_more),
            )
        }
    }
}
