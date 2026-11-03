package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.Platform

fun defaultGlobalShortcut(
    action: GlobalShortcutAction,
    platform: Platform,
) = GlobalShortcutBinding(
    key =
        when (action) {
            GlobalShortcutAction.QuickSearch -> GlobalShortcutKey.Space
            GlobalShortcutAction.LockVault -> GlobalShortcutKey.L
        },
    ctrl = platform !is Platform.Desktop.MacOS,
    meta = platform is Platform.Desktop.MacOS,
    shift = true,
)
