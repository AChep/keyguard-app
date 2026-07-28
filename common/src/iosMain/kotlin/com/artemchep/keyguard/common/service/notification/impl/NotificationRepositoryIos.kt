package com.artemchep.keyguard.common.service.notification.impl

import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.service.notification.NotificationRepository

object NotificationRepositoryIos : NotificationRepository {
    override fun post(notification: DNotification) = io<DNotificationKey?>(null)

    override fun delete(key: DNotificationKey) = ioUnit()
}
