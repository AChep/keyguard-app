package com.artemchep.keyguard.common.service.notification

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.model.DNotificationFingerprint

/**
 * @author Artem Chepurnyi
 */
interface NotificationFingerprintRepository {
    fun put(
        key: DNotificationKey,
        data: DNotificationFingerprint,
    ): IO<Unit>

    fun getByChannel(
        channel: DNotificationChannel,
    ): IO<List<DNotificationKey>>

    fun delete(
        key: DNotificationKey,
    ): IO<Unit>

    /**
     * Removes outdated notifications
     * entries.
     */
    fun cleanUp(): IO<Unit>
}
