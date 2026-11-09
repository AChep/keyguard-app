package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportFactory
import com.artemchep.keyguard.feature.p2psync.transport.TransportSessionContext

/**
 * What the transports of one Keyguard session, or one enrollment, get: the forwarding
 * transport of the relay connection, and the session's own context.
 * [TransportSessionContext.scope] ends with the session.
 */
class KeyguardTransportContext(
    val relayForward: RelayForwardTransport,
    val session: TransportSessionContext,
)

/**
 * Lists the transports of one session in the order they join the race, see
 * [TransportSelector]; relay forwarding always comes last. A device announces them to its
 * peers, which race only the ones both support. The application roots bind it: the
 * phone, desktop and Apple apps [prefer][preferring] WebRTC, while Wear OS and the
 * AutoFill extension use [RelayOnly].
 */
fun interface KeyguardTransportFactory {
    fun create(context: KeyguardTransportContext): List<Transport>

    companion object {
        /** Relay forwarding only. */
        val RelayOnly: KeyguardTransportFactory = KeyguardTransportFactory { context ->
            listOf(context.relayForward)
        }

        /** The transports [preferred] builds, such as WebRTC, then relay forwarding. */
        fun preferring(preferred: TransportFactory): KeyguardTransportFactory = KeyguardTransportFactory { context ->
            preferred.create(context.session) + context.relayForward
        }
    }
}
