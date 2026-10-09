package com.artemchep.keyguard.platform

internal fun supportsFido2Assertions(platform: Platform = CurrentPlatform): Boolean = when (platform) {
    is Platform.Mobile.Android -> !platform.isWatch
    is Platform.Desktop.Linux -> !platform.isFlatpak
    is Platform.Desktop.MacOS, Platform.Desktop.Windows -> true
    else -> false
}

