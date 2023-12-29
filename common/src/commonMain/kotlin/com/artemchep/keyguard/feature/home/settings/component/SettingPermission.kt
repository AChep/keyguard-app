package com.artemchep.keyguard.feature.home.settings.component

import org.kodein.di.DirectDI

expect fun settingPermissionDetailsProvider(
    directDI: DirectDI,
): SettingComponent
