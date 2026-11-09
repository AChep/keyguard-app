package com.artemchep.keyguard.apple.keyguardsync

/**
 * The devices of a sync group, or the linked apps, and the status of the group.
 * Texts are localized.
 */
data class KeyguardDevicesSnapshot(
    /** `false` until the producer emits. */
    val loaded: Boolean,
    val header: Header,
    /** `true` if this device was removed from the group; it no longer syncs. */
    val removed: Boolean,
    /** `true` if a device that was removed already removed this one: link the devices again. */
    val removedByRemoved: Boolean,
    /** The active devices of the group, this one first. */
    val devices: List<Device>,
    /** `false` once this device was removed, or while it may not add devices. */
    val canAddDevice: Boolean,
    val addDeviceTitle: String,
    val canLeave: Boolean,
    val leaveTitle: String,
    val leaveText: String,
) {
    /** The color of a status dot: how a device, or the devices of the group, are doing. */
    enum class Status {
        OK,
        WARNING,
        ERROR,

        /** Offline. */
        NEUTRAL,
    }

    data class Header(
        val status: Status,
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

    /**
     * A device of the group. A device seen before shows "Last seen" with the
     * time since [lastSeenAtMs] and [lastTransportText]; otherwise [statusText]
     * says how it is connected, or that it never was.
     */
    data class Device(
        val id: String,
        val label: String,
        /** The platform the device runs on: android, wear, ios, macos, windows, linux or desktop. */
        val platform: String,
        val fingerprint: String,
        val isThisDevice: Boolean,
        /** For example "Added Oct 29, 2026". */
        val addedText: String,
        /** `null` for this device. */
        val status: Status?,
        val statusText: String?,
        val lastSeenAtMs: Long?,
        val lastTransportText: String?,
        /** The other devices only. */
        val canRemove: Boolean,
    )

    companion object {
        val empty = KeyguardDevicesSnapshot(
            loaded = false,
            header = Header(
                status = Status.NEUTRAL,
                relayText = "",
                upToDate = 0,
                total = 0,
                upToDateText = null,
                storageText = null,
            ),
            removed = false,
            removedByRemoved = false,
            devices = emptyList(),
            canAddDevice = false,
            addDeviceTitle = "",
            canLeave = false,
            leaveTitle = "",
            leaveText = "",
        )
    }
}
