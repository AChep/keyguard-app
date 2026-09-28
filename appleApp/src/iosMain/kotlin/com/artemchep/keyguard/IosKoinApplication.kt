package com.artemchep.keyguard

import org.koin.core.module.Module
import org.koin.dsl.koinApplication

internal actual fun applePlatformModule(): Module = IosPlatformModule().module

/**
 * Assembles the iOS dependency graph without runtime overrides. `IosKoinGraphTest`
 * validates it, since the Koin compiler plugin cannot check cross-module definitions
 * on Kotlin/Native. The apps themselves build their graph in `CoreContext`.
 */
internal fun createIosKoinApplication() = koinApplication {
    allowOverride(false)
    modules(appleKoinModules())
}
