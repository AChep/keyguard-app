package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead
import com.artemchep.keyguard.common.usecase.ResetAllWatchtowerAlert
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.flow
import org.koin.core.scope.Scope

fun settingResetWatchtowerAlerts(
    koinScope: Scope,
) = settingResetWatchtowerAlerts(
    resetAllWatchtowerAlert = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
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
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Security,
        subIcon = Icons.Outlined.Refresh,
        title = "Reset watchtower alerts",
        onClick = onClick,
    )
}
