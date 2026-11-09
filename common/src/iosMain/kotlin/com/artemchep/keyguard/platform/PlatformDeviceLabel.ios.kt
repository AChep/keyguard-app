package com.artemchep.keyguard.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIDevice

internal actual suspend fun platformDeviceLabel(context: LeContext): String? = withContext(Dispatchers.Main) {
    // iOS 16+ returns a generic name without Apple's user-assigned-device-name entitlement.
    firstDeviceLabel({ UIDevice.currentDevice.name }, { UIDevice.currentDevice.localizedModel })
}
