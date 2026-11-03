package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.Platform
import kotlinx.serialization.Serializable

@Serializable
data class GlobalShortcutConfig(
    val enabled: Boolean = true,
    val binding: GlobalShortcutBinding? = null,
) {
    fun resolve(
        action: GlobalShortcutAction,
        platform: Platform,
    ): GlobalShortcutBinding = binding ?: defaultGlobalShortcut(action, platform)
}
