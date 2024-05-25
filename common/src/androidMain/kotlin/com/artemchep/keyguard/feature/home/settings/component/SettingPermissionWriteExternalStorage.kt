package com.artemchep.keyguard.feature.home.settings.component

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import org.kodein.di.DirectDI

actual fun settingPermissionWriteExternalStorageProvider(
    directDI: DirectDI,
): SettingComponent = settingPermissionWriteExternalStorageProvider()

@OptIn(ExperimentalPermissionsApi::class)
fun settingPermissionWriteExternalStorageProvider(): SettingComponent = settingPermissionProvider(
    leading = icon<RowScope>(Icons.Outlined.Storage),
    title = Res.string.pref_item_permission_write_external_storage_title,
    text = Res.string.pref_item_permission_write_external_storage_text,
    maxSdk = Build.VERSION_CODES.Q,
    permissionProvider = {
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    },
)
