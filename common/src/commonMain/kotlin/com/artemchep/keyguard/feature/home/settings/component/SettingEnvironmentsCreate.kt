package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.feature.environments.edit.EnvironmentEditRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_create
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingEnvironmentsCreateProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingEnvironmentsCreateProvider(
        environmentRepository = koinScope.get(),
    )
}

fun settingEnvironmentsCreateProvider(
    environmentRepository: EnvironmentRepository,
): SettingComponent = environmentRepository.state
    .map { state -> state.accounts.isNotEmpty() }
    .distinctUntilChanged()
    .map { hasAccounts ->
        SettingIi(
            platformClasses = environmentsPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "environments",
                    "env",
                    "new",
                    "create",
                ),
            ),
        ) {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingEnvironmentsCreate(
                onClick = if (hasAccounts) {
                    // lambda
                    {
                        val route = EnvironmentEditRoute()
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigationController.queue(intent)
                    }
                } else {
                    null
                },
            )
        }
    }

@Composable
private fun SettingEnvironmentsCreate(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Add,
        title = {
            Text(
                text = stringResource(Res.string.environments_create),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
