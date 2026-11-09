package com.artemchep.keyguard.common.model

/**
 * The live sync session of a sync group: its connection to the relay.
 *
 * @property revoked `true` once the session learned that this device
 *   was removed from the group.
 */
data class DKeyguardRelayStatus(
    val state: State,
    val revoked: Boolean = false,
) {
    enum class State {
        /** No relay room is open, for example while the app is in the background or alone. */
        OFFLINE,

        /** Joining the relay. */
        CONNECTING,

        /** Connected to the relay. */
        CONNECTED,

        /** The relay is out of reach; the session tries again. */
        FAILED,

        /** The relay speaks a protocol this version of the app can not use; the session tries again. */
        INCOMPATIBLE,
    }
}
