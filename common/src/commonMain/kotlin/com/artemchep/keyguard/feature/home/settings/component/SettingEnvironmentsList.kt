package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingEnvironmentsListProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingEnvironmentsListProvider(
        environmentRepository = koinScope.get(),
    )
}

fun settingEnvironmentsListProvider(
    environmentRepository: EnvironmentRepository,
): SettingComponent = environmentRepository.state
    .map { state -> state.environments.size }
    .distinctUntilChanged()
    .map { count ->
        SettingIi(
            platformClasses = environmentsPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "environments",
                    "env",
                    "variables",
                ),
            ),
        ) {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            val title = stringResource(Res.string.environments_title)
            SettingEnvironmentsList(
                count = count,
                onClick = {
                    // Environments are vault items, so the vault list
                    // filtered by type is where they are browsed.
                    val route = VaultRoute(
                        args = VaultRoute.Args(
                            appBar = VaultRoute.Args.AppBar(
                                title = title,
                            ),
                            filter = DFilter.ByType(DSecret.Type.Environment),
                        ),
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigationController.queue(intent)
                },
            )
        }
    }

@Composable
private fun SettingEnvironmentsList(
    count: Int,
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Code,
        badge = count.toString(),
        title = {
            Text(
                text = stringResource(Res.string.environments_title),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
