package com.artemchep.keyguard.common.service.notification

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationKey

/**
 * @author Artem Chepurnyi
 */
interface NotificationRepository {
    fun post(notification: DNotification): IO<DNotificationKey?>

    fun delete(
        key: DNotificationKey,
    ): IO<Unit>
}
