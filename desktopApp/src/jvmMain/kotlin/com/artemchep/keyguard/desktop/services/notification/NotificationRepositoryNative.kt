package com.artemchep.keyguard.desktop.services.notification

import com.artemchep.autotype.postNotification
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.platform.LeContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

class NotificationRepositoryNative(
    private val context: LeContext,
) : NotificationRepository {
    constructor(directDI: DirectDI) : this(
        context = directDI.instance(),
    )

    override fun post(notification: DNotification): IO<Unit> = ioEffect {
        val title = notification.title
        val text = notification.text.orEmpty()
        postNotification(
            id = notification.id.value,
            title = title,
            text = text,
        )
    }
}
