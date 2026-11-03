package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutsConfig

/** [statuses] holds every action whenever [config] is loaded. */
data class GlobalShortcutRuntimeState(
    val config: GlobalShortcutsConfig? = null,
    val statuses: Map<GlobalShortcutAction, GlobalShortcutStatus> = emptyMap(),
)
