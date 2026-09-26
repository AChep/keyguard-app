package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.service.backup.BackupConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Keeps potentially blocking storage work off the UI thread and checks editor lifetime before saving. */
internal suspend fun verifyAndSaveBackupSetup(
    config: BackupConfig,
    verify: suspend (BackupConfig) -> Unit,
    save: suspend (BackupConfig) -> Unit,
    isCurrent: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Boolean {
    withContext(dispatcher) { verify(config) }
    coroutineContext.ensureActive()
    if (!isCurrent()) return false
    withContext(dispatcher) { save(config) }
    coroutineContext.ensureActive()
    return isCurrent()
}
