package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationFingerprint
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import com.artemchep.keyguard.common.usecase.ShowNotification
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ShowNotificationImpl(
    private val notificationRepository: NotificationRepository,
    private val notificationFingerprintRepository: NotificationFingerprintRepository,
) : ShowNotification {
    constructor(directDI: DirectDI) : this(
        notificationRepository = directDI.instance(),
        notificationFingerprintRepository = directDI.instance(),
    )

    override fun invoke(notification: DNotification): IO<Unit> = notificationRepository
        .post(notification)
        // Save the info about notification into the
        // internal storage. This way we can reliably
        // cancel/update existing notifications.
        .flatMap { notificationKey ->
            if (notificationKey == null) {
                return@flatMap ioUnit()
            }

            val data = DNotificationFingerprint(
                channel = notification.channel,
            )
            notificationFingerprintRepository.put(
                key = notificationKey,
                data = data,
            )
        }
        .flatTap {
            notificationFingerprintRepository
                .cleanUp()
        }
}
