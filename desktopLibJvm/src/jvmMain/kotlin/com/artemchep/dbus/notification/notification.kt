package com.artemchep.dbus.notification

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

internal fun postNotificationDbus(
    id: Int,
    title: String,
    text: String,
): Boolean = runCatching {
    DBusConnectionBuilder.forSessionBus().build().use { conn ->
        val notifications: Notifications = conn.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            Notifications::class.java,
        )
        val hints = mutableMapOf<String, Variant<Byte>>();
        hints["urgency"] = Variant<Byte>(2)
        val id = notifications.Notify(
            "Keyguard",
            // The optional notification ID that this notification replaces. The server must
            // atomically (ie with no flicker or other visual cues) replace the given
            // notification with this one. This allows clients to effectively modify the
            // notification while it's active. A value of value of 0 means that this
            // notification won't replace any existing notifications.
            UInt32(0),
            "",
            title,
            text,
            arrayOf(),
            hints,
            0,
        ).toInt()
        id > 0
    }
}.getOrElse { e ->
    e.printStackTrace()
    false
}

@DBusInterfaceName("org.freedesktop.Notifications")
internal interface Notifications : DBusInterface {
    @DBusMemberName("Notify")
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: Array<String>,
        hints: MutableMap<String, Variant<Byte>>,
        timeout: Int,
    ): UInt32
}
