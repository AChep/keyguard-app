package com.artemchep.keyguard

import org.koin.core.module.Module

internal actual fun applePlatformModule(): Module = MacosPlatformModule().module
