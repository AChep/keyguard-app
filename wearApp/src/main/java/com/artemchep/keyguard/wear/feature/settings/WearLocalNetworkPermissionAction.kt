package com.artemchep.keyguard.wear.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.local_network_permission_banner_text
import com.artemchep.keyguard.res.local_network_permission_banner_title
import com.artemchep.keyguard.res.local_network_permission_open_settings_action
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.ui.WearListAction
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearLocalNetworkPermissionAction(
    modifier: Modifier = Modifier,
    permission: PermissionState.Declined,
    transformation: SurfaceTransformation,
) {
    val context = LocalLeContext
    val controller = LocalNavigationController.current
    val title = stringResource(Res.string.local_network_permission_banner_title)
    WearListAction(
        modifier = modifier,
        title = title,
        text = stringResource(Res.string.local_network_permission_banner_text),
        transformation = transformation,
        onClick = {
            controller.queue(
                NavigationIntent.NavigateToRoute(
                    WearPickerRoute(
                        title = title,
                        actions = listOf(
                            FlatItemAction(
                                title = Res.string.local_network_permission_banner_title.wrap(),
                                onClick = { permission.ask(context) },
                            ),
                            FlatItemAction(
                                title = Res.string.local_network_permission_open_settings_action.wrap(),
                                onClick = { permission.openSettings(context) },
                            ),
                        ),
                    ),
                ),
            )
        },
    )
}
