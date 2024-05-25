package com.artemchep.keyguard.feature.home.settings.component

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import org.kodein.di.DirectDI

actual fun settingPermissionPostNotificationsProvider(
    directDI: DirectDI,
): SettingComponent = settingPermissionPostNotificationsProvider2()

@OptIn(ExperimentalPermissionsApi::class)
fun settingPermissionPostNotificationsProvider2(): SettingComponent = settingPermissionProvider(
    leading = icon<RowScope>(Icons.Outlined.Notifications),
    title = Res.string.pref_item_permission_post_notifications_title,
    text = Res.string.pref_item_permission_post_notifications_text,
    minSdk = Build.VERSION_CODES.TIRAMISU,
    permissionProvider = {
        Manifest.permission.POST_NOTIFICATIONS
    },
)
