package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DKeyguardTransport
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Apps that may only use TURN, with the TURN credentials the relay mints for coturn. No
 * relay forwarding races it, so a slow TURN connection on a busy machine still counts.
 * Opt-in with `-Pkeyguard.p2pE2e.turn=true`; needs Docker, and fails without it.
 */
class TurnSyncE2eTest {
    @Test
    fun `two apps converge through TURN`() {
        assumeTrue("Pass -P$TURN_PROPERTY=true to run the TURN suite", System.getProperty(TURN_PROPERTY).toBoolean())
        CoturnContainer.start().use { coturn ->
            runCatching {
                p2pScenario(turn = coturn.turn) { testbed ->
                    val a = testbed.device("Desktop", Transports.turnOnly())
                    val mail = a.addForwarder("Mail")
                    val b = testbed.device("Phone", Transports.turnOnly())
                    link(member = a, joiner = b)
                    awaitConverged(a, b)

                    b.editForwarder(mail, name = "Through TURN")
                    val edited = awaitConverged(a, b)
                    assertEquals("Through TURN", edited.forwarder(mail)?.name)

                    awaitTransport(a, b, DKeyguardTransport.TURN)
                }
            }
                .onFailure { println(coturn.diagnostics()) }
                .getOrThrow()
        }
    }

    private companion object {
        const val TURN_PROPERTY = "keyguard.p2pE2e.turn"
    }
}
