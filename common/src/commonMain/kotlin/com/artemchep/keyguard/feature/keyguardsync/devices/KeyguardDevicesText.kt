package com.artemchep.keyguard.feature.keyguardsync.devices

import com.artemchep.keyguard.common.model.DKeyguardDevice
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_status_relay_connected
import com.artemchep.keyguard.res.app_link_status_relay_connecting
import com.artemchep.keyguard.res.app_link_status_relay_failed
import com.artemchep.keyguard.res.app_link_status_relay_incompatible
import com.artemchep.keyguard.res.app_link_status_relay_offline
import com.artemchep.keyguard.res.app_link_status_relay_storage_active
import com.artemchep.keyguard.res.app_link_status_relay_storage_paused
import com.artemchep.keyguard.res.app_link_status_relay_storage_starting
import com.artemchep.keyguard.res.app_link_status_relay_storage_too_large
import com.artemchep.keyguard.res.app_link_status_relay_storage_unsupported
import com.artemchep.keyguard.res.app_link_status_relay_storage_waiting
import com.artemchep.keyguard.res.app_link_status_relay_storage_waiting_plural
import com.artemchep.keyguard.res.app_link_transport_lan
import com.artemchep.keyguard.res.app_link_transport_relay
import com.artemchep.keyguard.res.app_link_transport_stun
import com.artemchep.keyguard.res.app_link_transport_turn
import org.jetbrains.compose.resources.StringResource

/** The name of the [DKeyguardTransport], as in "Last seen 5m ago via relay". */
val DKeyguardTransport.textRes: StringResource
    get() = when (this) {
        DKeyguardTransport.LAN -> Res.string.app_link_transport_lan
        DKeyguardTransport.STUN -> Res.string.app_link_transport_stun
        DKeyguardTransport.TURN -> Res.string.app_link_transport_turn
        DKeyguardTransport.RELAY -> Res.string.app_link_transport_relay
    }

/** The status line of the connection to the relay. */
val DKeyguardRelayStatus.State.textRes: StringResource
    get() = when (this) {
        DKeyguardRelayStatus.State.OFFLINE -> Res.string.app_link_status_relay_offline
        DKeyguardRelayStatus.State.CONNECTING -> Res.string.app_link_status_relay_connecting
        DKeyguardRelayStatus.State.CONNECTED -> Res.string.app_link_status_relay_connected
        DKeyguardRelayStatus.State.FAILED -> Res.string.app_link_status_relay_failed
        DKeyguardRelayStatus.State.INCOMPATIBLE -> Res.string.app_link_status_relay_incompatible
    }

suspend fun TranslatorScope.translateKeyguardRelayStatus(
    state: DKeyguardRelayStatus.State,
): String = translate(state.textRes)

/**
 * The status line of relay storage, or `null` while it is off: most devices keep it off,
 * so it says nothing new.
 */
suspend fun TranslatorScope.translateKeyguardRelayStorageStatus(
    status: DKeyguardRelayStorageStatus,
    dateFormatter: DateFormatter,
): String? = when (status) {
    DKeyguardRelayStorageStatus.Off -> null
    DKeyguardRelayStorageStatus.Unsupported -> translate(Res.string.app_link_status_relay_storage_unsupported)
    is DKeyguardRelayStorageStatus.Waiting -> when (val count = status.devices.size) {
        // This device's own record does not allow it yet.
        0 -> translate(Res.string.app_link_status_relay_storage_starting)
        1 -> translate(Res.string.app_link_status_relay_storage_waiting, status.devices.single())
        else -> translate(Res.plurals.app_link_status_relay_storage_waiting_plural, count, count)
    }

    DKeyguardRelayStorageStatus.Active -> translate(Res.string.app_link_status_relay_storage_active)
    is DKeyguardRelayStorageStatus.Paused -> translate(
        Res.string.app_link_status_relay_storage_paused,
        dateFormatter.formatDateTime(status.until),
    )

    DKeyguardRelayStorageStatus.TooLarge -> translate(Res.string.app_link_status_relay_storage_too_large)
}

/**
 * How the devices of a sync group are doing, as this device sees them.
 *
 * @property total the other active devices.
 * @property upToDate the other devices that hold the state of this one.
 */
data class KeyguardDevicesSummary(
    val total: Int,
    val upToDate: Int,
    /** `true` if this device was removed from the group. */
    val removed: Boolean,
    /** `true` if only devices that are no longer active removed this one, see [DKeyguardDevice.removedByRemoved]. */
    val removedByRemoved: Boolean = false,
)

/**
 * How the devices of the group are doing, from the [relay] and the [summary].
 */
fun keyguardDevicesHealth(
    relay: DKeyguardRelayStatus.State,
    summary: KeyguardDevicesSummary,
): KeyguardDevicesState.Health = when (relay) {
    DKeyguardRelayStatus.State.FAILED,
    DKeyguardRelayStatus.State.INCOMPATIBLE,
    -> if (summary.upToDate == 0) {
        KeyguardDevicesState.Health.ERROR
    } else {
        KeyguardDevicesState.Health.WARNING
    }

    DKeyguardRelayStatus.State.CONNECTED -> if (summary.upToDate == summary.total) {
        KeyguardDevicesState.Health.OK
    } else {
        KeyguardDevicesState.Health.WARNING
    }

    DKeyguardRelayStatus.State.OFFLINE -> KeyguardDevicesState.Health.NEUTRAL
    DKeyguardRelayStatus.State.CONNECTING -> KeyguardDevicesState.Health.WARNING
}

/** How this device reaches another device of the group, over the [connection]. */
fun keyguardDeviceHealth(
    connection: DKeyguardPeerStatus.Connection,
): KeyguardDevicesState.Health = when (connection) {
    is DKeyguardPeerStatus.Connection.Connected -> if (connection.upToDate) {
        KeyguardDevicesState.Health.OK
    } else {
        KeyguardDevicesState.Health.WARNING
    }

    DKeyguardPeerStatus.Connection.Connecting -> KeyguardDevicesState.Health.WARNING
    DKeyguardPeerStatus.Connection.Offline -> KeyguardDevicesState.Health.NEUTRAL
}

fun keyguardDevicesSummary(
    devices: List<DKeyguardDevice>,
    peers: Map<String, DKeyguardPeerStatus>,
    relay: DKeyguardRelayStatus,
): KeyguardDevicesSummary {
    val active = devices.filter { !it.revoked }
    val others = active.filter { !it.isThisDevice }
    val connections = others.map { peers[it.id]?.connection }
    return KeyguardDevicesSummary(
        total = others.size,
        upToDate = connections.count { it is DKeyguardPeerStatus.Connection.Connected && it.upToDate },
        removed = relay.revoked || devices.any { it.isThisDevice && it.revoked },
        removedByRemoved = devices.any { it.isThisDevice && it.removedByRemoved },
    )
}
