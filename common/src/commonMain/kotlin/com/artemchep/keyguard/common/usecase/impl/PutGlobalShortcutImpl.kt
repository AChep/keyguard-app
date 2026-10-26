package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.GlobalShortcutException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutController
import com.artemchep.keyguard.common.usecase.PutGlobalShortcut

class PutGlobalShortcutImpl(
    private val controller: GlobalShortcutController,
) : PutGlobalShortcut {
    override fun invoke(
        action: GlobalShortcutAction,
        transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
    ): IO<Unit> = {
        controller.update(action, transform)
            ?.let { throw GlobalShortcutException(it) }
    }
}
