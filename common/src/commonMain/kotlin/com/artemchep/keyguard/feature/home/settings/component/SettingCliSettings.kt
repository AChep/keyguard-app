package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.cli.CliSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cli_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingCliSettingsProvider(
    koinScope: Scope,
) = cliSettingProvider {
    settingCliSettingsProvider(
        cliService = koinScope.get(),
    )
}

fun settingCliSettingsProvider(
    cliService: CliService,
): SettingComponent = cliService.state
    // Builds that serve no command of the CLI, such as Flatpak, do not offer it.
    .map { state -> state.supported }
    .distinctUntilChanged()
    .map { supported ->
        if (!supported) {
            return@map null
        }

        SettingIi(
            platformClasses = cliPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "cli",
                    "command",
                    "terminal",
                    "run",
                ),
            ),
        ) {
            val navigation by rememberUpdatedState(LocalNavigationController.current)
            LocalSettingPaneComponents.current.KgAction(
                icon = Icons.Outlined.Terminal,
                title = { Text(stringResource(Res.string.cli_title)) },
                trailing = { ChevronIcon() },
                onClick = { navigation.queue(NavigationIntent.NavigateToRoute(CliSettingsRoute)) },
            )
        }
    }
