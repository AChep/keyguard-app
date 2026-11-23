package com.artemchep.dbus.tray

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusNotifierHostTest {
    @Test
    fun `maps registered host variant`() {
        assertTrue(Variant(true).toStatusNotifierHostRegistered())
    }

    @Test
    fun `maps unregistered host variant`() {
        assertFalse(Variant(false).toStatusNotifierHostRegistered())
    }

    @Test
    fun `maps unwrapped boolean`() {
        assertTrue(true.toStatusNotifierHostRegistered())
    }

    @Test
    fun `maps nested variant`() {
        assertTrue(Variant(Variant(true)).toStatusNotifierHostRegistered())
    }

    @Test
    fun `treats malformed value as no host`() {
        assertFalse("true".toStatusNotifierHostRegistered())
        assertFalse(null.toStatusNotifierHostRegistered())
    }

    @Test
    fun `only a confirmed absent host is unavailable`() {
        assertTrue(true.isAvailable())
        assertFalse(false.isAvailable())
        // A failed read, such as a bus address the transport can't reach, keeps the tray.
        assertTrue(null.isAvailable())
    }

    @Test
    fun `accepts watcher owner change`() {
        val result = isStatusNotifierHostChange(
            interfaceName = "org.freedesktop.DBus",
            member = "NameOwnerChanged",
            arg0 = "org.kde.StatusNotifierWatcher",
        )

        assertTrue(result)
    }

    @Test
    fun `ignores other owner changes`() {
        val result = isStatusNotifierHostChange(
            interfaceName = "org.freedesktop.DBus",
            member = "NameOwnerChanged",
            arg0 = "org.freedesktop.Notifications",
        )

        assertFalse(result)
    }

    @Test
    fun `only watcher owner change is a new watcher`() {
        assertTrue(
            isStatusNotifierWatcherOwnerChange(
                interfaceName = "org.freedesktop.DBus",
                member = "NameOwnerChanged",
                arg0 = "org.kde.StatusNotifierWatcher",
            ),
        )
        assertFalse(
            isStatusNotifierWatcherOwnerChange(
                interfaceName = "org.freedesktop.DBus",
                member = "NameOwnerChanged",
                arg0 = "org.freedesktop.Notifications",
            ),
        )
        // A host that comes and goes keeps the watcher and its registered icons.
        assertFalse(
            isStatusNotifierWatcherOwnerChange(
                interfaceName = "org.kde.StatusNotifierWatcher",
                member = "StatusNotifierHostRegistered",
                arg0 = null,
            ),
        )
    }

    @Test
    fun `accepts host registration signals`() {
        for (member in listOf("StatusNotifierHostRegistered", "StatusNotifierHostUnregistered")) {
            val result = isStatusNotifierHostChange(
                interfaceName = "org.kde.StatusNotifierWatcher",
                member = member,
                arg0 = null,
            )

            assertTrue(result, member)
        }
    }

    @Test
    fun `ignores item registration signals`() {
        val result = isStatusNotifierHostChange(
            interfaceName = "org.kde.StatusNotifierWatcher",
            member = "StatusNotifierItemRegistered",
            arg0 = ":1.42",
        )

        assertFalse(result)
    }

    @Test
    fun `ignores same member on another interface`() {
        val result = isStatusNotifierHostChange(
            interfaceName = "org.example.Watcher",
            member = "StatusNotifierHostRegistered",
            arg0 = null,
        )

        assertFalse(result)
    }
}
