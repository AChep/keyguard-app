package com.artemchep.keyguard.common.service.urlblock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Keeps the exposed copy of the blocked URIs equal to the exposed rules of the
 * vault, including the ones that linked apps synced, so that autofill blocks them
 * while the vault is locked.
 */
interface UrlBlockExposedSyncer {
    fun launch(scope: CoroutineScope): Job
}
