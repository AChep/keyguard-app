package com.artemchep.keyguard.common.service.shortcut

internal class GlobalShortcutPersistenceRollbackException(
    cause: Throwable,
    rollback: Throwable,
) : IllegalStateException("Could not save or restore global shortcuts", cause) {
    init {
        addSuppressed(rollback)
    }
}
