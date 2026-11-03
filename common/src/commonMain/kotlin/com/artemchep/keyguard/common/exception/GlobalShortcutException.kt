package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError

class GlobalShortcutException(
    val error: GlobalShortcutError,
    cause: Throwable? = null,
) : RuntimeException("Global shortcut operation failed: $error", cause)
