package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.environments.EnvironmentsMcpSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.protocol_mcp
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

@Suppress("UnusedParameter") // Setting provider signature includes its composition scope.
fun settingEnvironmentsMcpSettingsProvider(koinScope: Scope): SettingComponent = environmentsSettingProvider {
    flowOf(
        SettingIi(
            platformClasses = environmentsPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf("environments", "mcp", "agent"),
            ),
        ) {
            val navigation by rememberUpdatedState(LocalNavigationController.current)
            LocalSettingPaneComponents.current.KgAction(
                icon = Icons.Outlined.Code,
                title = { Text(stringResource(Res.string.protocol_mcp)) },
                trailing = { ChevronIcon() },
                onClick = { navigation.queue(NavigationIntent.NavigateToRoute(EnvironmentsMcpSettingsRoute)) },
            )
        },
    )
}
