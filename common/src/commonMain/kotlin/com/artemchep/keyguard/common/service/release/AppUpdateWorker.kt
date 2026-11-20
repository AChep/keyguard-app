package com.artemchep.keyguard.common.service.release

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Checks for new releases of the app once a day, and posts a
 * notification once per new release.
 */
interface AppUpdateWorker {
    fun launch(scope: CoroutineScope): Job
}
