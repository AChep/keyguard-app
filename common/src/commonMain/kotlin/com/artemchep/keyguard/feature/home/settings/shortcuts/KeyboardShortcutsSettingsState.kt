package com.artemchep.keyguard.feature.home.settings.shortcuts

import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutStatus

data class KeyboardShortcutsSettingsState(
    val items: List<Item>,
    val busy: Boolean,
) {
    data class Item(
        val action: GlobalShortcutAction,
        val config: GlobalShortcutConfig,
        val status: GlobalShortcutStatus,
        val error: GlobalShortcutError?,
        val onEdit: () -> Unit,
        val onEnabledChange: (Boolean) -> Unit,
        val onReset: () -> Unit,
    )
}
