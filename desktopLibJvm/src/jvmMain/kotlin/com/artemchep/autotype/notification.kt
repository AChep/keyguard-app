package com.artemchep.autotype

import com.artemchep.dbus.notification.postNotificationDbus
import com.sun.jna.Platform

public suspend fun postNotification(
    id: Int,
    title: String,
    text: String,
): Boolean {
    if (Platform.isLinux()) {
        return postNotificationDbus(
            id = id,
            title = title,
            text = text,
        )
    }

    // Not supported
    return false
}
