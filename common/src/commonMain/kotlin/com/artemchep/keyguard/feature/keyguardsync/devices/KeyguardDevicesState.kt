package com.artemchep.keyguard.feature.keyguardsync.devices

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlin.time.Instant

@Immutable
data class KeyguardDevicesState(
    val header: Header,
    /** `true` if this device was removed from the group; it no longer syncs. */
    val removed: Boolean,
    /** `true` if a device that was removed already removed this one: link the devices again. */
    val removedByRemoved: Boolean = false,
    /** The active devices of the group, this one first. */
    val devices: ImmutableList<Device>,
    /**
     * Adds a device, showing or scanning a code; empty once this device was removed, or
     * while it may not add devices.
     */
    val addDeviceActions: ImmutableList<ContextItem>,
    /** Asks, then takes this device out of the sync group. */
    val onLeave: (() -> Unit)?,
    val texts: Texts,
) {
    /** How a device, or the devices of the group, are doing, as a status dot shows. */
    enum class Health {
        OK,
        WARNING,
        ERROR,

        /** Nothing to say, such as without a live session. */
        NEUTRAL,
    }

    @Immutable
    data class Header(
        val health: Health,
        /** The status line of the relay. */
        val relayText: String,
        /** The other devices that hold the state of this one. */
        val upToDate: Int,
        /** The other active devices. */
        val total: Int,
        /** For example "1 of 2 devices up to date"; `null` without other devices. */
        val upToDateText: String?,
        /** The status line of relay storage; `null` while it is off, or no session runs. */
        val storageText: String?,
    )

    @Immutable
    data class Device(
        val id: String,
        val label: String,
        /** The platform the device runs on: android, wear, ios, macos, windows, linux or desktop. */
        val platform: String,
        val fingerprint: String,
        val isThisDevice: Boolean,
        /** For example "Added Oct 29, 2026". */
        val addedText: String,
        /** How this device reaches the device; `null` for this device. */
        val health: Health?,
        /**
         * How the device is connected, or that it never was; `null` for this
         * device, and while [lastSeen] says when the devices last had a session.
         */
        val statusText: String?,
        val lastSeen: LastSeen?,
        /** Asks, then removes the device; the other devices only. */
        val onRemove: (() -> Unit)?,
        /** [onRemove] as a menu item. */
        val dropdown: ImmutableList<ContextItem>,
    )

    /**
     * When the devices last had a session, and over what. The screens show the time
     * since then, so that it stays current.
     */
    @Immutable
    data class LastSeen(
        val at: Instant,
        /** For example "relay". */
        val transportText: String,
    )

    /** The texts that depend on what the group syncs. */
    @Immutable
    data class Texts(
        val addDeviceTitle: String,
        val leaveTitle: String,
        val leaveText: String,
    )
}
