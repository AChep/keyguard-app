package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.combineSeq
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DismissNotificationsByChannelImpl(
    private val notificationRepository: NotificationRepository,
    private val notificationFingerprintRepository: NotificationFingerprintRepository,
) : DismissNotificationsByChannel {
    constructor(directDI: DirectDI) : this(
        notificationRepository = directDI.instance(),
        notificationFingerprintRepository = directDI.instance(),
    )

    override fun invoke(
        channel: DNotificationChannel,
    ): IO<Unit> = notificationFingerprintRepository
        .getByChannel(channel)
        .flatMap { keys ->
            keys
                .map { key ->
                    notificationRepository.delete(key)
                        .flatMap {
                            notificationFingerprintRepository
                                .delete(key)
                        }
                }
                .combineSeq()
                // Hide the result.
                .effectMap { }
        }
}
