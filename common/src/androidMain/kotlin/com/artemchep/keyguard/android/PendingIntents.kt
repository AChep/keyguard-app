package com.artemchep.keyguard.android

import com.artemchep.keyguard.android.downloader.NotificationIdPool

object PendingIntents {
    val autofill = NotificationIdPool.sequential(0)
    val credential = NotificationIdPool.sequential(
        start = 1000000,
        endExclusive = 1100000,
    )
    val notification = NotificationIdPool.sequential(
        start = 1100000,
        endExclusive = 1200000,
    )
}
