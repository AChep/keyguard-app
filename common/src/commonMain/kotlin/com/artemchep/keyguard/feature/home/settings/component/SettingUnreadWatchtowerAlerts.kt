package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.flow
import org.koin.core.scope.Scope

fun settingUnreadWatchtowerAlerts(
    koinScope: Scope,
) = settingUnreadWatchtowerAlerts(
    markAllWatchtowerAlertAsNotRead = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingUnreadWatchtowerAlerts(
    markAllWatchtowerAlertAsNotRead: MarkAllWatchtowerAlertAsNotRead,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flow {
    val component = if (!isRelease) {
        SettingIi {
            SettingUnreadWatchtowerAlerts(
                onClick = {
                    markAllWatchtowerAlertAsNotRead()
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
private fun SettingUnreadWatchtowerAlerts(
    onClick: (() -> Unit),
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Security,
        subIcon = Icons.Outlined.Notifications,
        title = "Unread watchtower alerts",
        onClick = onClick,
    )
}
