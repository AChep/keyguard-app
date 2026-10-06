package com.artemchep.keyguard

import kotlin.concurrent.Volatile

enum class LaunchAtLoginStatus {
    ENABLED,
    DISABLED,
    REQUIRES_APPROVAL,
    UNAVAILABLE,
}

interface LaunchAtLoginBridge {
    /** Reads the live registration status (never a cached flag). */
    fun status(): LaunchAtLoginStatus

    /** Registers ([enabled] = true) or unregisters the main app as a login item. */
    fun setEnabled(enabled: Boolean)

    /** Opens System Settings ▸ Login Items so the user can approve the item. */
    fun openLoginItemsSettings()
}

object LaunchAtLoginBridgeRegistry {
    @Volatile
    var bridge: LaunchAtLoginBridge? = null
}

fun registerLaunchAtLoginBridge(
    bridge: LaunchAtLoginBridge,
) {
    LaunchAtLoginBridgeRegistry.bridge = bridge
}
