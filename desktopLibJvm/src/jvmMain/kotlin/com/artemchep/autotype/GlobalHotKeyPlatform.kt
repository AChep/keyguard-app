package com.artemchep.autotype

import com.sun.jna.Platform

internal enum class GlobalHotKeyPlatform {
    MacOS,
    Windows,
    Linux,
    Unsupported,
}

internal fun currentGlobalHotKeyPlatform(): GlobalHotKeyPlatform =
    when {
        Platform.isMac() -> GlobalHotKeyPlatform.MacOS
        Platform.isWindows() || Platform.isWindowsCE() -> GlobalHotKeyPlatform.Windows
        Platform.isLinux() -> GlobalHotKeyPlatform.Linux
        else -> GlobalHotKeyPlatform.Unsupported
    }
