package com.artemchep.keyguard.android.agentbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentBridgeAdmissionTest {
    @Test
    fun `pending and active sessions share one hard capacity`() {
        val nowMs = 100L
        val admission =
            AgentBridgeAdmission(
                capacity = 2,
                nowMs = { nowMs },
            )

        val first = admission.reserve("first")
        val second = admission.reserve("second")

        assertTrue(admission.markActive(first.sessionId))
        assertIs<AgentBridgeAdmission.ReserveResult.Busy>(
            admission.tryReserve("third"),
        )
        assertTrue(admission.release(first.sessionId))
        assertIs<AgentBridgeAdmission.ReserveResult.Reserved>(
            admission.tryReserve("third"),
        )
        assertTrue(admission.canStart(second.sessionId))
    }

    @Test
    fun `duplicate session does not consume another slot`() {
        val admission =
            AgentBridgeAdmission(
                capacity = 2,
                nowMs = { 100L },
            )

        admission.reserve("same")

        assertIs<AgentBridgeAdmission.ReserveResult.Duplicate>(
            admission.tryReserve("same"),
        )
        assertIs<AgentBridgeAdmission.ReserveResult.Reserved>(
            admission.tryReserve("other"),
        )
    }

    @Test
    fun `deferred reservation can be claimed by recovery service`() =
        runTest {
            val admission =
                AgentBridgeAdmission(
                    capacity = 1,
                    nowMs = { 100L },
                )
            val reservation = admission.reserve("session")

            assertTrue(admission.markDeferred(reservation.sessionId))
            assertEquals(
                AgentBridgeContract.BroadcastOutcome.DEFERRED,
                reservation.outcome.await(),
            )
            assertTrue(admission.canStart(reservation.sessionId))
            assertTrue(admission.markActive(reservation.sessionId))
            assertFalse(admission.canStart(reservation.sessionId))
        }

    @Test
    fun `expired pending reservation releases capacity and rejects late start`() =
        runTest {
            var nowMs = 100L
            val admission =
                AgentBridgeAdmission(
                    capacity = 1,
                    nowMs = { nowMs },
                )
            val reservation =
                admission.reserve(
                    sessionId = "expired",
                    pendingTtlMs = 10L,
                )

            nowMs = 110L

            assertFalse(admission.canStart(reservation.sessionId))
            assertEquals(
                AgentBridgeContract.BroadcastOutcome.START_FAILED,
                reservation.outcome.await(),
            )
            assertIs<AgentBridgeAdmission.ReserveResult.Reserved>(
                admission.tryReserve("replacement"),
            )
        }

    @Test
    fun `receiver rejection cannot remove an active bridge`() {
        val admission =
            AgentBridgeAdmission(
                capacity = 1,
                nowMs = { 100L },
            )
        val reservation = admission.reserve("active")
        assertTrue(admission.markActive(reservation.sessionId))

        assertFalse(
            admission.reject(
                sessionId = reservation.sessionId,
                outcome = AgentBridgeContract.BroadcastOutcome.START_FAILED,
            ),
        )
        assertIs<AgentBridgeAdmission.ReserveResult.Busy>(
            admission.tryReserve("other"),
        )
    }

    @Test
    fun `broadcast outcome wire values are versioned and unique`() {
        val outcomes = AgentBridgeContract.BroadcastOutcome.entries
        val prefixes = mapOf(
            AgentBridge.SSH to "keyguard.ssh-agent.broadcast.v1:",
            AgentBridge.GPG to "keyguard.gpg-agent.broadcast.v1:",
        )

        val wireValues = prefixes.flatMap { (bridge, prefix) ->
            outcomes.map { outcome ->
                outcome.wireValue(bridge).also { assertTrue(it.startsWith(prefix)) }
            }
        }
        assertEquals(wireValues.size, wireValues.distinct().size)
    }

    @Test
    fun `each agent has its own intent contract`() {
        val contracts = AgentBridge.entries.map { bridge ->
            listOf(
                bridge.action,
                bridge.extraProtocolVersion,
                bridge.extraProxyPort,
                bridge.extraSessionId,
                bridge.extraSessionSecret,
            )
        }

        assertEquals(contracts.flatten().size, contracts.flatten().distinct().size)
        AgentBridge.entries.forEach { bridge ->
            assertEquals(bridge, AgentBridge.ofAction(bridge.action))
        }
        assertEquals(null, AgentBridge.ofAction("com.artemchep.keyguard.action.UNKNOWN"))
    }

    private fun AgentBridgeAdmission.reserve(
        sessionId: String,
        pendingTtlMs: Long = 60_000L,
    ): AgentBridgeAdmission.Reservation {
        val result =
            tryReserve(
                sessionId = sessionId,
                pendingTtlMs = pendingTtlMs,
            )
        return assertIs<AgentBridgeAdmission.ReserveResult.Reserved>(result).reservation
    }
}
