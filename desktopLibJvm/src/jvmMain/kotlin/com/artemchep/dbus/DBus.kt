package com.artemchep.dbus

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/** A private session bus connection, so closing it never affects other users. */
internal fun newSessionConnection(): DBusConnection =
    DBusConnectionBuilder.forSessionBus()
        .withShared(false)
        .build()

internal tailrec fun Any?.unwrapVariant(): Any? =
    if (this is Variant<*>) {
        value.unwrapVariant()
    } else {
        this
    }
