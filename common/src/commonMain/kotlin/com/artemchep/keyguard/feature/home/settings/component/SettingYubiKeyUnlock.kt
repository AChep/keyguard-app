package com.artemchep.keyguard.feature.home.settings.component

import org.kodein.di.DirectDI

expect fun settingYubiKeyUnlockProvider(
    directDI: DirectDI,
): SettingComponent
