package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.keyguard.common.model.GlobalShortcutAction

sealed interface GlobalShortcutError {
    data class Duplicate(
        val action: GlobalShortcutAction,
    ) : GlobalShortcutError

    data object Invalid : GlobalShortcutError

    data object UnsupportedPlatform : GlobalShortcutError

    data object UnsupportedSession : GlobalShortcutError

    data object Unavailable : GlobalShortcutError

    data object Registration : GlobalShortcutError

    data object Persistence : GlobalShortcutError

    data object PersistenceRestoration : GlobalShortcutError
}
