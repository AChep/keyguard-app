package com.artemchep.keyguard.feature.p2psync.transport

/** How a direct connection reaches the peer, as the UI names it. */
enum class TransportPath {
    /** Directly, on the same network. */
    HOST,

    /** Across NATs, with addresses learnt through STUN. */
    SRFLX,

    /** Through a TURN server. */
    RELAY,
    ;

    companion object {
        /**
         * Returns the path of a connection between ICE candidates of the [types], such as
         * `host`, `srflx` or `relay`: the less direct one.
         */
        fun of(vararg types: String): TransportPath = when {
            "relay" in types -> RELAY
            "srflx" in types || "prflx" in types -> SRFLX
            else -> HOST
        }
    }
}
