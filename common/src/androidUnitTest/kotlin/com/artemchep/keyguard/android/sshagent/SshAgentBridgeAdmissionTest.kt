package com.artemchep.keyguard.android.sshagent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SshAgentBridgeAdmissionTest {
    @Test
    fun `pending and active sessions share one hard capacity`() {
        val nowMs = 100L
        val admission =
            SshAgentBridgeAdmission(
                capacity = 2,
                nowMs = { nowMs },
            )

        val first = admission.reserve("first")
        val second = admission.reserve("second")

        assertTrue(admission.markActive(first.sessionId))
        assertIs<SshAgentBridgeAdmission.ReserveResult.Busy>(
            admission.tryReserve("third"),
        )
        assertTrue(admission.release(first.sessionId))
        assertIs<SshAgentBridgeAdmission.ReserveResult.Reserved>(
            admission.tryReserve("third"),
        )
        assertTrue(admission.canStart(second.sessionId))
    }

    @Test
    fun `duplicate session does not consume another slot`() {
        val admission =
            SshAgentBridgeAdmission(
                capacity = 2,
                nowMs = { 100L },
            )

        admission.reserve("same")

        assertIs<SshAgentBridgeAdmission.ReserveResult.Duplicate>(
            admission.tryReserve("same"),
        )
        assertIs<SshAgentBridgeAdmission.ReserveResult.Reserved>(
            admission.tryReserve("other"),
        )
    }

    @Test
    fun `deferred reservation can be claimed by recovery service`() =
        runTest {
            val admission =
                SshAgentBridgeAdmission(
                    capacity = 1,
                    nowMs = { 100L },
                )
            val reservation = admission.reserve("session")

            assertTrue(admission.markDeferred(reservation.sessionId))
            assertEquals(
                SshAgentContract.BroadcastOutcome.DEFERRED,
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
                SshAgentBridgeAdmission(
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
                SshAgentContract.BroadcastOutcome.START_FAILED,
                reservation.outcome.await(),
            )
            assertIs<SshAgentBridgeAdmission.ReserveResult.Reserved>(
                admission.tryReserve("replacement"),
            )
        }

    @Test
    fun `receiver rejection cannot remove an active bridge`() {
        val admission =
            SshAgentBridgeAdmission(
                capacity = 1,
                nowMs = { 100L },
            )
        val reservation = admission.reserve("active")
        assertTrue(admission.markActive(reservation.sessionId))

        assertFalse(
            admission.reject(
                sessionId = reservation.sessionId,
                outcome = SshAgentContract.BroadcastOutcome.START_FAILED,
            ),
        )
        assertIs<SshAgentBridgeAdmission.ReserveResult.Busy>(
            admission.tryReserve("other"),
        )
    }

    @Test
    fun `broadcast outcome wire values are versioned and unique`() {
        val outcomes = SshAgentContract.BroadcastOutcome.entries

        assertEquals(outcomes.size, outcomes.map { it.wireValue }.distinct().size)
        assertTrue(outcomes.all { it.wireValue.startsWith("keyguard.ssh-agent.broadcast.v1:") })
    }

    private fun SshAgentBridgeAdmission.reserve(
        sessionId: String,
        pendingTtlMs: Long = 60_000L,
    ): SshAgentBridgeAdmission.Reservation {
        val result =
            tryReserve(
                sessionId = sessionId,
                pendingTtlMs = pendingTtlMs,
            )
        return assertIs<SshAgentBridgeAdmission.ReserveResult.Reserved>(result).reservation
    }
}
