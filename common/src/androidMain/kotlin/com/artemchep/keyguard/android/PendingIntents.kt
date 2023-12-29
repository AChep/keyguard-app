package com.artemchep.keyguard.android

import com.artemchep.keyguard.android.downloader.NotificationIdPool

object PendingIntents {
    val autofill = NotificationIdPool.sequential(0)
    val credential = NotificationIdPool.sequential(
        start = 100000,
        endExclusive = 200000,
    )
}
