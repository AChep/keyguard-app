package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.common.model.DKeyguardSyncTooLarge
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath

/**
 * The sync status of one sync group on this device.
 *
 * @property relay the connection of the session to its relay room.
 * @property peers the other active devices of the group, by device id.
 * @property revoked `true` if this device was removed from the group.
 * @property lastError why the last connection attempt failed, for diagnostics.
 * @property tooLarge set while a session finds the local state too large to send.
 * @property storage whether the relay keeps the state of the group for the devices that
 *   are offline.
 */
data class KeyguardSessionStatus(
    val relay: DKeyguardRelayStatus.State = DKeyguardRelayStatus.State.CONNECTING,
    val peers: Map<String, DKeyguardPeerStatus.Connection> = emptyMap(),
    val revoked: Boolean = false,
    val lastError: String? = null,
    val tooLarge: DKeyguardSyncTooLarge? = null,
    val storage: DKeyguardRelayStorageStatus = DKeyguardRelayStorageStatus.Off,
)

/**
 * Returns how a session over a transport of [kind] reaches the peer; [path] is the one
 * a WebRTC connection settled on, if it did.
 */
internal fun keyguardTransportOf(
    kind: TransportKind,
    path: TransportPath?,
): DKeyguardTransport = when (kind) {
    TransportKind.RELAY -> DKeyguardTransport.RELAY
    TransportKind.WEBRTC -> when (path) {
        TransportPath.RELAY -> DKeyguardTransport.TURN
        TransportPath.SRFLX -> DKeyguardTransport.STUN
        TransportPath.HOST,
        null,
        -> DKeyguardTransport.LAN
    }
}

/** The value of the transport in `p2pPeerState.lastTransport`. */
internal val DKeyguardTransport.storedValue: String
    get() = when (this) {
        DKeyguardTransport.LAN -> "lan"
        DKeyguardTransport.STUN -> "stun"
        DKeyguardTransport.TURN -> "turn"
        DKeyguardTransport.RELAY -> "relay"
    }

/** Returns the transport stored in `p2pPeerState.lastTransport`, or `null` if it is unknown. */
internal fun keyguardTransportOf(value: String): DKeyguardTransport? = DKeyguardTransport.entries
    .firstOrNull { it.storedValue == value }
