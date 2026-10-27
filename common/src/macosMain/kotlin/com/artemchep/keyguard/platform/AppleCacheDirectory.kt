package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.resolve

internal actual fun appleKeyguardCacheDirectory(): LocalPath =
    appleKeyguardDataDirectory().resolve("cache")
