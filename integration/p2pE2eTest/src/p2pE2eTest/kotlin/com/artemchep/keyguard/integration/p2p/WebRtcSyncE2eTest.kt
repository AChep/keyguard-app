package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.integration.p2p.P2pE2eDevice.Companion.API_KEY
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Apps with the transports of the phone, desktop and Apple apps: WebRTC first, relay
 * forwarding last. The relay only signals while WebRTC connects.
 */
class WebRtcSyncE2eTest {
    @Test
    fun `two apps converge over a direct WebRTC connection`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.webRtc())
        val mail = a.addForwarder("Mail")
        val b = testbed.device("Phone", Transports.webRtc())
        link(member = a, joiner = b)
        awaitConverged(a, b)

        b.editForwarder(mail, data = mapOf(API_KEY to "from phone"))
        val bank = a.addForwarder("Bank")
        val edited = awaitConverged(a, b)
        assertEquals(mapOf(API_KEY to "from phone"), edited.forwarder(mail)?.data)
        assertEquals("Bank", edited.forwarder(bank)?.name)

        // Host candidates on loopback: what two devices on one network use.
        awaitTransport(a, b, DKeyguardTransport.LAN)
    }

    @Test
    fun `apps go over relay forwarding when WebRTC can not connect`() = p2pScenario { testbed ->
        // TURN only, and the relay offers no TURN server: WebRTC never connects, and
        // relay forwarding wins the race.
        val a = testbed.device("Desktop", Transports.webRtcRelayPolicy())
        val mail = a.addForwarder("Mail")
        val b = testbed.device("Phone", Transports.webRtcRelayPolicy())
        link(member = a, joiner = b)
        awaitConverged(a, b)

        b.editForwarder(mail, name = "Personal mail")
        val edited = awaitConverged(a, b)
        assertEquals("Personal mail", edited.forwarder(mail)?.name)

        awaitTransport(a, b, DKeyguardTransport.RELAY)
    }
}
