package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.usecase.ShowNotification
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ShowNotificationImpl(
    private val notificationRepository: NotificationRepository,
) : ShowNotification {
    constructor(directDI: DirectDI) : this(
        notificationRepository = directDI.instance(),
    )

    override fun invoke(notification: DNotification): IO<Unit> = notificationRepository
        .post(notification)
}
