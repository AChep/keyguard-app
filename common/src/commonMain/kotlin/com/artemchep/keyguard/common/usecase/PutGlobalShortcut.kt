package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.exception.GlobalShortcutException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig

interface PutGlobalShortcut {
    /**
     * Returns an effect that applies [transform] to the [action]'s latest saved
     * configuration, and throws [GlobalShortcutException] if the update is rejected.
     */
    operator fun invoke(
        action: GlobalShortcutAction,
        transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
    ): IO<Unit>
}
