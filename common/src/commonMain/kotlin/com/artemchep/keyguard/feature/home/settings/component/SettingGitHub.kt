package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import compose.icons.FeatherIcons
import compose.icons.feathericons.Github
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingGitHubProvider(
    directDI: DirectDI,
): SettingComponent = settingGitHubProvider()

fun settingGitHubProvider(): SettingComponent = kotlin.run {
    // Do not render the field if there's nothing
    // to show its full content in.
    if (!CurrentPlatform.hasBrowser()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "code",
                "github",
                "open",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingGitHub(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://github.com/AChep/keyguard-app/",
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingGitHub(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = FeatherIcons.Github,
        subIcon = Icons.Outlined.KeyguardWebsite,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.pref_item_github_title),
        onClick = onClick,
    )
}
