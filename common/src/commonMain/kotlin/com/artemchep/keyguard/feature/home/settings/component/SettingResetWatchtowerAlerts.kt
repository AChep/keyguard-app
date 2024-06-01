package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead
import com.artemchep.keyguard.common.usecase.ResetAllWatchtowerAlert
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingResetWatchtowerAlerts(
    directDI: DirectDI,
) = settingResetWatchtowerAlerts(
    resetAllWatchtowerAlert = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingResetWatchtowerAlerts(
    resetAllWatchtowerAlert: ResetAllWatchtowerAlert,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flow {
    val component = if (!isRelease) {
        SettingIi {
            SettingResetWatchtowerAlerts(
                onClick = {
                    resetAllWatchtowerAlert()
                        .launchIn(windowCoroutineScope)
                },
            )
        }
    } else {
        null
    }
    emit(component)
}

@Composable
private fun SettingResetWatchtowerAlerts(
    onClick: (() -> Unit),
) {
    VaultViewButtonItem(
        leading = icon<RowScope>(Icons.Outlined.Security, Icons.Outlined.Refresh),
        text = "Reset watchtower alerts",
        onClick = onClick,
    )
}
