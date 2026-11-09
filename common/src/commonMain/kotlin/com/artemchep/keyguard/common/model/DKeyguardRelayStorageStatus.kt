package com.artemchep.keyguard.common.model

import kotlin.time.Instant

/**
 * Whether the relay keeps the state of a sync group for the devices that are offline:
 * relay storage, see `P2pNetwork.relayStorage`. The group uses it only while every active
 * device turned it on and the relay keeps items.
 */
sealed interface DKeyguardRelayStorageStatus {
    /** This device has relay storage off. */
    data object Off : DKeyguardRelayStorageStatus

    /** The relay keeps no items: the devices sync while they are online together only. */
    data object Unsupported : DKeyguardRelayStorageStatus

    /**
     * Other devices of the group have relay storage off, or this device did not hand its
     * own choice to them yet: [devices] names the labels of the ones it waits for, if any.
     */
    data class Waiting(
        val devices: List<String>,
    ) : DKeyguardRelayStorageStatus

    /** The relay keeps the state of this device for the others, and theirs for it. */
    data object Active : DKeyguardRelayStorageStatus

    /** The relay is full, or keeps no items for now: nothing is stored [until] then. */
    data class Paused(
        val until: Instant,
    ) : DKeyguardRelayStorageStatus

    /** The state of this device is larger than the relay keeps: it syncs live only. */
    data object TooLarge : DKeyguardRelayStorageStatus
}
