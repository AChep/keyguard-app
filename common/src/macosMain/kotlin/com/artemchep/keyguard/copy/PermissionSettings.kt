package com.artemchep.keyguard.copy

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

internal actual fun openPermissionSettings() {
    val url = NSURL.fileURLWithPath("/System/Applications/System Settings.app")
    NSWorkspace.sharedWorkspace.openURL(url)
}
