package com.artemchep.keyguard.feature.home.settings.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import arrow.core.partially1
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRoute
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

actual fun settingPermissionDetailsProvider(
    directDI: DirectDI,
): SettingComponent = settingPermissionDetailsProvider()

fun settingPermissionDetailsProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingPermissionDetails(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = PermissionsSettingsRoute,
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
    FlatItemSimpleExpressive(
        leading = icon<RowScope>(Icons.Outlined.DeveloperMode),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_permissions_title),
            )
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalPermissionsApi::class)
fun settingPermissionProvider(
    leading: @Composable RowScope.() -> Unit,
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
                    leading = leading,
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
    leading: @Composable RowScope.() -> Unit,
    title: String,
    text: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemSimpleExpressive(
        leading = leading,
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(title)
        },
        text = {
            Text(text)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
