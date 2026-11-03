package com.artemchep.keyguard.common.service.shortcut

sealed interface GlobalShortcutStatus {
    data object Active : GlobalShortcutStatus

    data object Disabled : GlobalShortcutStatus

    data object Recording : GlobalShortcutStatus

    data class Failed(
        val error: GlobalShortcutError,
    ) : GlobalShortcutStatus
}
