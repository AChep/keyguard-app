package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.P2pGroupId
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A device asks its peers for their roster and envelope again over the sessions that run,
 * as the app does once a category is turned on again. Linked apps with real Noise
 * handshakes and the real sync pass on in-memory databases.
 */
class KeyguardSessionResendTest {
    @Test
    fun `a category turned on again gets the state of the peer over the running session`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            b.app.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, false).bind()
            a.startLive()
            b.startLive()
            awaitConnected(a, b)
            val clients = group.relay.clients.size

            // B takes the envelope that holds the forwarder, and drops the forwarder.
            a.addForwarder("Mail")
            a.sync()
            delay(QUIET_MS)
            assertTrue(b.forwarders().isEmpty())

            // On again: the peer already sent its envelope, and sends it again only once asked.
            b.app.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, true).bind()
            delay(QUIET_MS)
            assertTrue(b.forwarders().isEmpty())
            b.registry.requestResend(P2pGroupId.APP_LINK_ID)
            eventually { "Mail" in b.forwarders() }
            // Over the session that ran all along.
            assertEquals(clients, group.relay.clients.size)
            awaitConnected(a, b)
        }
    }

    private companion object {
        /** Long enough for a pass to merge what arrived. */
        const val QUIET_MS = 1_500L
    }
}
