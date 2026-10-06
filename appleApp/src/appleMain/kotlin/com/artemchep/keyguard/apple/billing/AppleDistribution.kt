package com.artemchep.keyguard.apple.billing

import platform.Foundation.NSBundle

/** Explicit build metadata also exists in the extension's own bundle. Missing metadata is paid. */
internal fun appleIsFreeDistribution(): Boolean =
    NSBundle.mainBundle.objectForInfoDictionaryKey("KeyguardDistribution") == "direct"
