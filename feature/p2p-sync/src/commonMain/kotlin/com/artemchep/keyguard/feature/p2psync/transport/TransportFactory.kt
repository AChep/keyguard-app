package com.artemchep.keyguard.feature.p2psync.transport

import kotlinx.coroutines.CoroutineScope

/** A STUN or TURN server; TURN URLs come with short-lived credentials. */
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    // The credential is a secret.
    override fun toString(): String = "IceServer(urls=$urls)"
}

/**
 * What the transports of one session share: the room's [signaling], this device, the ICE
 * servers, and a [scope] that ends with the session and closes whatever the transports
 * opened.
 *
 * @property allowLocalNetwork `false` keeps direct connections off the local network.
 * @property onSelectedPath called with the path a direct connection to a peer settled on.
 *   It must not block.
 */
class TransportSessionContext(
    val localDeviceId: String,
    val signaling: Signaling,
    val iceServers: suspend () -> List<IceServer>,
    val scope: CoroutineScope,
    val allowLocalNetwork: Boolean = true,
    val onSelectedPath: (peerId: String, path: TransportPath) -> Unit = { _, _ -> },
)

/** Builds the transports of one session that are tried before the relay, such as WebRTC. */
fun interface TransportFactory {
    fun create(context: TransportSessionContext): List<Transport>
}
