package com.artemchep.autotype

import com.artemchep.dbus.notification.postNotificationDbus
import com.artemchep.jna.util.asMemory
import com.artemchep.jna.withDesktopLib
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

    return withDesktopLib { lib ->
        lib.postNotification(
            id = id,
            title = title
                .asMemory()
                .let(::register),
            text = text
                .asMemory()
                .let(::register),
        )
    }
}
