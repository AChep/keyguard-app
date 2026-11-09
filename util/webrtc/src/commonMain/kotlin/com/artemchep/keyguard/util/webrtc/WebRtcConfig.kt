package com.artemchep.keyguard.util.webrtc

/**
 * The configuration of one [WebRtcPeer].
 *
 * ICE mDNS is always off: no `.local` candidates are published or resolved.
 *
 * @param bindAddresses literal local UDP socket addresses to bind, such as
 * `127.0.0.1:0`. Empty binds every interface of both address families.
 */
data class WebRtcConfig(
    val iceServers: List<IceServer> = emptyList(),
    val iceTransportPolicy: WebRtcIceTransportPolicy = WebRtcIceTransportPolicy.ALL,
    val bindAddresses: List<String> = emptyList(),
) {
    /** A STUN or TURN server; TURN URLs need [username] and [credential]. */
    data class IceServer(
        val urls: List<String>,
        val username: String? = null,
        val credential: String? = null,
    ) {
        // The credential is a secret.
        override fun toString(): String = "IceServer(urls=$urls)"
    }
}

/** Which local candidates ICE may use. */
enum class WebRtcIceTransportPolicy(internal val wire: String) {
    /** Host, server-reflexive and relay candidates. */
    ALL("all"),

    /** Relay candidates only. */
    RELAY("relay"),
}
