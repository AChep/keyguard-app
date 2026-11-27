package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.secretscan.SecretScanRoute
import com.artemchep.keyguard.feature.secretscan.ui
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingPlaintextSshKeyScanProvider(
    koinScope: Scope,
): SettingComponent = settingPlaintextSecretScanProvider(koinScope, SecretScanKind.SshKey)

fun settingPlaintextDotEnvScanProvider(
    koinScope: Scope,
): SettingComponent = settingPlaintextSecretScanProvider(koinScope, SecretScanKind.DotEnv)

private fun settingPlaintextSecretScanProvider(
    koinScope: Scope,
    kind: SecretScanKind,
): SettingComponent {
    // Bound only where the app can read the user's files directly.
    koinScope.getOrNull<PlaintextSecretScanner>()
        ?: return flowOf(null)
    val ui = kind.ui
    return flowOf(
        SettingIi(
            search = SettingIi.Search(
                group = "developer",
                tokens = ui.searchTokens,
            ),
        ) {
            val navigation by rememberUpdatedState(LocalNavigationController.current)
            LocalSettingPaneComponents.current.KgAction(
                icon = ui.icon,
                title = { Text(stringResource(ui.title)) },
                trailing = {
                    ChevronIcon()
                },
                onClick = {
                    val route = SecretScanRoute(kind)
                    navigation.queue(NavigationIntent.NavigateToRoute(route))
                },
            )
        },
    )
}
