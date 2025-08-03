package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.PutMarkdown
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.icon
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
        SettingMarkdown(
            checked = markdown,
            onCheckedChange = onCheckedChange,
            onLearnMore = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://www.markdownguide.org/basic-syntax/",
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
    FlatItemLayoutExpressive(
        leading = icon<RowScope>(Icons.Outlined.TextFormat),
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
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_markdown_title),
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.pref_item_markdown_text),
                    )
                },
            )
        },
        footer = {
            TextButton(
                modifier = Modifier
                    .padding(
                        horizontal = 46.dp,
                        vertical = 4.dp,
                    ),
                enabled = onLearnMore != null,
                onClick = {
                    onLearnMore?.invoke()
                },
            ) {
                Text(
                    text = stringResource(Res.string.learn_more),
                )
            }
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
