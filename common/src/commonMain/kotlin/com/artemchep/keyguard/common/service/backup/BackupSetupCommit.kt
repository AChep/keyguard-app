package com.artemchep.keyguard.common.service.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Keeps potentially blocking storage work off the UI thread and checks editor lifetime before saving. */
suspend fun verifyAndSaveBackupSetup(
    config: BackupConfig,
    verify: suspend (BackupConfig) -> Unit,
    save: suspend (BackupConfig) -> Unit,
    isCurrent: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean {
    val config = config.sanitized()
    withContext(dispatcher) { verify(config) }
    coroutineContext.ensureActive()
    if (!isCurrent()) return false
    withContext(dispatcher) { save(config) }
    coroutineContext.ensureActive()
    return isCurrent()
}
