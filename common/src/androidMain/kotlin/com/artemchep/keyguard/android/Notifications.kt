package com.artemchep.keyguard.android

import com.artemchep.keyguard.android.downloader.NotificationIdPool

object Notifications {
    val downloads = NotificationIdPool.sequential(
        start = 10000,
        endExclusive = 20000,
    )
    val uploads = NotificationIdPool.sequential(20000)
    val totp = NotificationIdPool.sequential(30000)
    val export = NotificationIdPool.sequential(
        start = 40000,
        endExclusive = 50000,
    )
}
