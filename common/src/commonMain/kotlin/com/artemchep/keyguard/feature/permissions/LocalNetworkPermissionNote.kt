package com.artemchep.keyguard.feature.permissions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.GetLocalNetworkAccessHint
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.local_network_permission_banner_text
import com.artemchep.keyguard.res.local_network_permission_banner_title
import com.artemchep.keyguard.res.local_network_permission_open_settings_action
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun rememberLocalNetworkPermission(): PermissionState.Declined? {
    val service = koinInject<PermissionService>()
    val state by remember(service) {
        service.getState(Permission.LOCAL_NETWORK)
    }.collectAsState(initial = PermissionState.Granted)
    return state as? PermissionState.Declined
}

@Composable
fun rememberLocalNetworkPermissionHint(): PermissionState.Declined? {
    val permission = rememberLocalNetworkPermission() ?: return null
    val getHint = koinInject<GetLocalNetworkAccessHint>()
    val showHint by remember(getHint) { getHint() }.collectAsState(initial = false)
    return permission.takeIf { showHint }
}

/** An optional permission action: public servers remain usable without LAN access. */
@Composable
fun LocalNetworkPermissionNote(
    modifier: Modifier = Modifier,
    permission: PermissionState.Declined,
) {
    val context = LocalLeContext
    FlatSimpleNote(
        modifier = modifier,
        type = SimpleNote.Type.INFO,
        title = stringResource(Res.string.local_network_permission_banner_title),
        text = stringResource(Res.string.local_network_permission_banner_text),
        onClick = { permission.ask(context) },
        trailing = {
            IconButton(onClick = { permission.openSettings(context) }) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(Res.string.local_network_permission_open_settings_action),
                )
            }
        },
    )
}
