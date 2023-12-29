package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.apppicker.AppPickerResult
import com.artemchep.keyguard.feature.apppicker.AppPickerRoute
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.UUID

fun settingLaunchAppPicker(
    directDI: DirectDI,
) = settingLaunchAppPicker(
    showMessage = directDI.instance(),
)

fun settingLaunchAppPicker(
    showMessage: ShowMessage,
): SettingComponent = flow {
    val component = if (!isRelease) {
        SettingIi {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingLaunchYubiKey(
                onClick = {
                    val route = registerRouteResultReceiver(AppPickerRoute) { result ->
                        if (result is AppPickerResult.Confirm) {
                            val model = ToastMessage(
                                title = result.uri,
                                text = UUID.randomUUID().toString(),
                            )
                            showMessage.copy(model)
                        }
                    }
                    val intent = NavigationIntent.NavigateToRoute(
                        route = route,
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
        leading = icon<RowScope>(Icons.Outlined.Apps),
        text = "App Picker",
        onClick = onClick,
    )
}
