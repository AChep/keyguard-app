package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.yubikey.YubiRoute
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI

fun settingLaunchYubiKey(
    directDI: DirectDI,
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
    VaultViewButtonItem(
        leading = icon<RowScope>(Icons.Outlined.Key),
        text = "YubiKey",
        onClick = onClick,
    )
}
