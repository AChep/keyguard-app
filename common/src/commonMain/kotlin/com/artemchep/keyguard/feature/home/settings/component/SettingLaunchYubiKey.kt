package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.yubikey.YubiRoute
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.icons.KeyguardYubiKey
import kotlinx.coroutines.flow.flow
import org.koin.core.scope.Scope

fun settingLaunchYubiKey(
    koinScope: Scope,
) = settingLaunchYubiKey()

fun settingLaunchYubiKey(): SettingComponent = flow {
    val component = if (!isRelease) {
        SettingIi {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingLaunchYubiKey(
                onClick = {
                    val intent = NavigationIntent.NavigateToRoute(
                        route = YubiRoute,
                    )
                    navigationController.queue(intent)
                },
            )
        }
    } else {
        null
    }
    emit(component)
}

@Composable
private fun SettingLaunchYubiKey(
    onClick: (() -> Unit),
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.KeyguardYubiKey,
        title = "YubiKey",
        onClick = onClick,
    )
}
