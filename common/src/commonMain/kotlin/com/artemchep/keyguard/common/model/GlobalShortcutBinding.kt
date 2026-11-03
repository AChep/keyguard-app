package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.Platform
import kotlinx.serialization.Serializable

@Serializable
data class GlobalShortcutBinding(
    val key: GlobalShortcutKey,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    fun isValid(platform: Platform): Boolean =
        (ctrl || alt || meta) &&
            !(platform is Platform.Desktop.MacOS && key == GlobalShortcutKey.Insert)
}
