package com.artemchep.keyguard.feature.home.settings.component

import org.koin.core.scope.Scope

expect fun settingPermissionDetailsProvider(
    koinScope: Scope,
): SettingComponent
