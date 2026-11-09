package com.artemchep.keyguard.common.model

import kotlin.time.Instant

/** How this device reaches another device of its sync group. */
enum class DKeyguardTransport {
    /** Directly, on the same network. */
    LAN,

    /** Directly, across networks. */
    STUN,

    /** Through a TURN server. */
    TURN,

    /** Through the relay. */
    RELAY,
}

/**
 * What this device knows about another device of its sync group.
 *
 * @property lastSeenAt when the devices last had a session, or `null` if never.
 * @property lastTransport how the devices reached each other the last time.
 */
data class DKeyguardPeerStatus(
    val deviceId: String,
    val connection: Connection,
    val lastSeenAt: Instant?,
    val lastTransport: DKeyguardTransport?,
) {
    sealed interface Connection {
        /** Not reachable right now, or no live session runs. */
        data object Offline : Connection

        /** Opening a connection. */
        data object Connecting : Connection

        /**
         * Syncing over the [transport]. [upToDate] is `true` once the other
         * device holds the state of this one, and while none of its own state
         * is arriving. [tooLarge] is `true` while the state of the other device
         * is too large to arrive.
         */
        data class Connected(
            val transport: DKeyguardTransport,
            val upToDate: Boolean,
            val tooLarge: Boolean = false,
        ) : Connection
    }
}
