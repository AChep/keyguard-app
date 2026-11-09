package com.artemchep.keyguard.common.model

/**
 * The sync status of a sync group on this device.
 *
 * @property relay the live sync session of the group.
 * @property peers what this device knows about the other devices of the group,
 *   by device id.
 * @property tooLarge set while the synced state of this device is too large to send.
 * @property storage whether the relay keeps the state of the group for the devices that
 *   are offline, or `null` while no session runs.
 */
data class DKeyguardSyncStatus(
    val relay: DKeyguardRelayStatus,
    val peers: Map<String, DKeyguardPeerStatus>,
    val tooLarge: DKeyguardSyncTooLarge? = null,
    val storage: DKeyguardRelayStorageStatus? = null,
)

/**
 * The synced state of this device is [size] bytes, more than the [limit] a sync session
 * carries: its changes no longer reach the other devices.
 */
data class DKeyguardSyncTooLarge(
    val size: Int,
    val limit: Int,
)
