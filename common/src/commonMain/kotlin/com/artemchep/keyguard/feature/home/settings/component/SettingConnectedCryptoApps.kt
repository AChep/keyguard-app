package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.feature.androidipc.ConnectedCryptoAppsRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.connected_crypto_apps_summary
import com.artemchep.keyguard.res.connected_crypto_apps_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingConnectedAppsProvider(
    directDI: DirectDI,
): SettingComponent {
    val registrations = directDI.instance<AndroidIpcRegistrationService>()
    return registrations.registrations().map { apps ->
        SettingIi(
            platformClasses = listOf(Platform.Mobile.Android::class),
            search = SettingIi.Search(
                group = "security",
                tokens = listOf("openpgp", "gpg", "ssh", "apps"),
            ),
        ) {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingConnectedApps(
                count = apps.size,
                onClick = {
                    val intent = NavigationIntent.NavigateToRoute(ConnectedCryptoAppsRoute)
                    navigationController.queue(intent)
                },
            )
        }
    }
}

@Composable
private fun SettingConnectedApps(
    count: Int,
    onClick: () -> Unit,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Apps,
        badge = count.toString()
            .takeIf { count > 0 },
        title = {
            Text(
                text = stringResource(Res.string.connected_crypto_apps_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.connected_crypto_apps_summary),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
