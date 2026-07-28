package com.artemchep.keyguard.common.service.gpgkeyserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface GpgKeyserverRefreshWorker {
    fun launch(scope: CoroutineScope): Job
}
