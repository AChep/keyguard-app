package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.Platform

/**
 * macOS 27 assigns Shift+Cmd+Space to Visual Intelligence, so Quick search
 * uses Option+Cmd+\ by default from this version on.
 */
private const val MACOS_QUICK_SEARCH_BACKSLASH_MAJOR_VERSION = 27

fun defaultGlobalShortcut(
    action: GlobalShortcutAction,
    platform: Platform,
): GlobalShortcutBinding {
    val isMacOS = platform is Platform.Desktop.MacOS
    val macosMajorVersion = (platform as? Platform.Desktop.MacOS)?.majorVersion ?: 0
    val quickSearchUsesBackslash = macosMajorVersion >= MACOS_QUICK_SEARCH_BACKSLASH_MAJOR_VERSION
    if (action == GlobalShortcutAction.QuickSearch && quickSearchUsesBackslash) {
        return GlobalShortcutBinding(
            key = GlobalShortcutKey.Backslash,
            alt = true,
            meta = true,
        )
    }
    return GlobalShortcutBinding(
        key =
            when (action) {
                GlobalShortcutAction.QuickSearch -> GlobalShortcutKey.Space
                GlobalShortcutAction.LockVault -> GlobalShortcutKey.L
            },
        ctrl = !isMacOS,
        meta = isMacOS,
        shift = true,
    )
}
