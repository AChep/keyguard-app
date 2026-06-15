package com.artemchep.keyguard.feature.home.settings.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactory
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

actual fun settingPermissionDetailsProvider(
    directDI: DirectDI,
) = settingPermissionDetailsProvider(
    permissionsSettingsRouteFactory = directDI.instance(),
)

fun settingPermissionDetailsProvider(
    permissionsSettingsRouteFactory: PermissionsSettingsRouteFactory,
): SettingComponent = kotlin.run {
    if (CurrentPlatform.hasWatch()) {
        return@run flowOf(null)
    }

    val item = SettingIi {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingPermissionDetails(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = permissionsSettingsRouteFactory.create(),
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingPermissionDetails(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.DeveloperMode,
        title = stringResource(Res.string.pref_item_permissions_title),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalPermissionsApi::class)
fun settingPermissionProvider(
    icon: ImageVector,
    subIcon: ImageVector? = null,
    title: StringResource,
    text: StringResource,
    minSdk: Int = Int.MIN_VALUE,
    maxSdk: Int = Int.MAX_VALUE,
    permissionProvider: () -> String,
): SettingComponent = kotlin.run {
    if (Build.VERSION.SDK_INT in minSdk..maxSdk) {
        kotlin.run {
            val item = SettingIi {
                val permissionState = rememberPermissionState(permissionProvider())

                val updatedContext by rememberUpdatedState(newValue = LocalContext.current)
                val updatedStatus by rememberUpdatedState(newValue = permissionState.status)
                SettingPermission(
                    icon = icon,
                    subIcon = subIcon,
                    title = stringResource(title),
                    text = stringResource(text),
                    checked = updatedStatus.isGranted,
                    onCheckedChange = { shouldBeChecked ->
                        val isChecked = updatedStatus.isGranted
                        if (isChecked != shouldBeChecked) {
                            if (shouldBeChecked) {
                                permissionState.launchPermissionRequest()
                            } else {
                                updatedContext.launchAppDetailsSettings()
                            }
                        }
                    },
                )
            }
            flowOf(item)
        }
    } else {
        flowOf(null)
    }
}

fun Context.launchAppDetailsSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }
    kotlin.runCatching {
        startActivity(intent)
    }
}

@Composable
private fun SettingPermission(
    icon: ImageVector,
    subIcon: ImageVector?,
    title: String,
    text: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = icon,
        subIcon = subIcon,
        title = title,
        text = text,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
