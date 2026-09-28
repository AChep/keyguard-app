package com.artemchep.keyguard

import com.artemchep.keyguard.core.session.usecase.PlatformVaultModule
import com.artemchep.keyguard.di.GlobalModuleCommon
import com.artemchep.keyguard.di.VaultModuleCommon
import com.artemchep.keyguard.feature.navigation.NavigationModule
import org.koin.core.module.Module

/** Platform bindings of the current Apple target (iOS or macOS). */
internal expect fun applePlatformModule(): Module

/** The complete application graph of the native Apple apps, in load order. */
internal fun appleKoinModules(): List<Module> = listOf(
    GlobalModuleCommon().module,
    VaultModuleCommon().module,
    PlatformVaultModule().module,
    applePlatformModule(),
    NavigationModule().module,
)
