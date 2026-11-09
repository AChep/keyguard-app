package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.feature.p2psync.session.CloseReason
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.provider.keyguard.transport.IceServersGrant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Calls [reconnect] once the TURN credentials of [iceServers], which this device gave the
 * connection of a session, reach [IceServersGrant.REFRESH_PERCENT] of their lifetime, while
 * [overTurn] says that the connection goes through TURN: coturn drops it once they expire.
 * The session then ends with [CloseReason.RECONNECT], and both devices connect again at
 * once, with fresh credentials.
 *
 * Returns at once for a connection that never goes through TURN: one of another [kind]
 * than WebRTC, or one given no TURN credentials. A direct path never reconnects.
 */
internal suspend fun reconnectBeforeTurnExpires(
    kind: TransportKind,
    iceServers: IceServersGrant?,
    overTurn: Flow<Boolean>,
    reconnect: suspend () -> Unit,
) {
    if (kind != TransportKind.WEBRTC || iceServers == null || !iceServers.hasTurn) return
    overTurn
        .distinctUntilChanged()
        .collectLatest { turn ->
            if (turn) {
                delay(iceServers.msUntilRefresh() ?: return@collectLatest)
                reconnect()
            }
        }
}
