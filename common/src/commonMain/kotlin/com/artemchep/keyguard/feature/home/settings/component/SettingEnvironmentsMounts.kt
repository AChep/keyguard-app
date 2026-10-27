package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.feature.environments.mounts.EnvironmentMountsRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_mounts
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingEnvironmentsMountsProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingEnvironmentsMountsProvider(
        environmentMounts = koinScope.get(),
    )
}

fun settingEnvironmentsMountsProvider(
    environmentMounts: EnvironmentMounts,
): SettingComponent = environmentMounts.state
    .map { state ->
        // Windows has no named pipes for local .env files.
        state.mounts.count { it.enabled }.takeIf { state.supported }
    }
    .distinctUntilChanged()
    .map { count ->
        if (count == null) {
            return@map null
        }

        SettingIi(
            platformClasses = environmentsPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "environments",
                    "env",
                    "dotenv",
                    "mount",
                    "pipe",
                ),
            ),
        ) {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingEnvironmentsMounts(
                count = count,
                onClick = {
                    val intent = NavigationIntent.NavigateToRoute(EnvironmentMountsRoute)
                    navigationController.queue(intent)
                },
            )
        }
    }

@Composable
private fun SettingEnvironmentsMounts(
    count: Int,
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Description,
        badge = count.toString(),
        title = {
            Text(
                text = stringResource(Res.string.environments_mounts),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
