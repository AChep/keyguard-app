package com.artemchep.keyguard.feature.home.settings.component

import org.koin.core.scope.Scope

expect fun settingPermissionWriteExternalStorageProvider(
    koinScope: Scope,
): SettingComponent
