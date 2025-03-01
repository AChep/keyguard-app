package com.artemchep.keyguard.common.service.notification

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DNotification

/**
 * @author Artem Chepurnyi
 */
interface NotificationRepository {
    fun post(notification: DNotification): IO<Unit>
}
