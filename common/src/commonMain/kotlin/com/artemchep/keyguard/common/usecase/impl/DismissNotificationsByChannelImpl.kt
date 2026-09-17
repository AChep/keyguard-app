package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.combineSeq
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel

class DismissNotificationsByChannelImpl(
    private val notificationRepository: NotificationRepository,
    private val notificationFingerprintRepository: NotificationFingerprintRepository,
) : DismissNotificationsByChannel {
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
