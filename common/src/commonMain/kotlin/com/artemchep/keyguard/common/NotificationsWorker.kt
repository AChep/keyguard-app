package com.artemchep.keyguard.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface NotificationsWorker {
    fun launch(scope: CoroutineScope): Job
}
