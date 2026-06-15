package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.PutMarkdown
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingMarkdownProvider(
    directDI: DirectDI,
) = settingMarkdownProvider(
    getMarkdown = directDI.instance(),
    putMarkdown = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingMarkdownProvider(
    getMarkdown: GetMarkdown,
    putMarkdown: PutMarkdown,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getMarkdown().map { markdown ->
    val onCheckedChange = { shouldMarkdown: Boolean ->
        putMarkdown(shouldMarkdown)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "ui",
            tokens = listOf(
                "markdown",
                "format",
                "note",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        val hasBrowser = CurrentPlatform
            .hasBrowser()
        SettingMarkdown(
            checked = markdown,
            onCheckedChange = onCheckedChange,
            onLearnMore = if (hasBrowser) {
                // lambda
                {
                    val intent = NavigationIntent.NavigateToBrowser(
                        url = "https://www.markdownguide.org/basic-syntax/",
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
        icon = Icons.Outlined.TextFormat,
        title = stringResource(Res.string.pref_item_markdown_title),
        text = stringResource(Res.string.pref_item_markdown_text),
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
