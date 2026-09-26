package com.artemchep.keyguard.feature.home.settings.component

import kotlinx.coroutines.flow.flowOf
import org.koin.core.scope.Scope

actual fun settingPermissionWriteExternalStorageProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)
