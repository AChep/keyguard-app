package com.artemchep.keyguard.feature.home.settings.component

import android.Manifest
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import org.kodein.di.DirectDI

actual fun settingPermissionCameraProvider(
    directDI: DirectDI,
): SettingComponent = settingPermissionCameraProvider2()

@OptIn(ExperimentalPermissionsApi::class)
fun settingPermissionCameraProvider2(): SettingComponent = settingPermissionProvider(
    icon = Icons.Outlined.CameraAlt,
    title = Res.string.pref_item_permission_camera_title,
    text = Res.string.pref_item_permission_camera_text,
    minSdk = Build.VERSION_CODES.M,
    permissionProvider = {
        Manifest.permission.CAMERA
    },
)
