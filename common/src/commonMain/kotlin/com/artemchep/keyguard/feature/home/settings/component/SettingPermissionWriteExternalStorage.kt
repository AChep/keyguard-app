package com.artemchep.keyguard.feature.home.settings.component

import org.kodein.di.DirectDI

expect fun settingPermissionWriteExternalStorageProvider(
    directDI: DirectDI,
): SettingComponent
