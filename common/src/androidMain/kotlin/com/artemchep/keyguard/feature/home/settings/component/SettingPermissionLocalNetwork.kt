package com.artemchep.keyguard.feature.home.settings.component

import android.Manifest
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

actual fun settingPermissionLocalNetworkProvider(
    koinScope: Scope,
): SettingComponent = settingPermissionLocalNetworkProvider2()

@OptIn(ExperimentalPermissionsApi::class)
fun settingPermissionLocalNetworkProvider2(): SettingComponent {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return flowOf(null)
    return flowOf(SettingIi {
        val permission = rememberPermissionState(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val context by rememberUpdatedState(LocalContext.current)
        LocalSettingPaneComponents.current.KgSwitch(
            icon = Icons.Outlined.Wifi,
            title = stringResource(Res.string.pref_item_permission_local_network_title),
            text = stringResource(Res.string.pref_item_permission_local_network_text),
            checked = permission.status.isGranted,
            onCheckedChange = { grant ->
                if (grant) {
                    permission.launchPermissionRequest()
                } else {
                    context.launchAppDetailsSettings()
                }
            },
            footer = if (!permission.status.isGranted) {
                {
                    // Also available when Android no longer displays the permission prompt.
                    TextButton(onClick = { context.launchAppDetailsSettings() }) {
                        Text(stringResource(Res.string.local_network_permission_open_settings_action))
                    }
                }
            } else {
                null
            },
        )
    })
}
