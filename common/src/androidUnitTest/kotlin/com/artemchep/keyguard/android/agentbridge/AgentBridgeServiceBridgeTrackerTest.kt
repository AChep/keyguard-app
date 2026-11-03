package com.artemchep.keyguard.android.agentbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentBridgeServiceBridgeTrackerTest {
    @Test
    fun `duplicate pending starts are rejected without limiting active bridges`() {
        val tracker = AgentBridgeServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 1))
        assertTrue(tracker.tryReserve(startId = 2))
        assertFalse(tracker.tryReserve(startId = 2))

        assertTrue(tracker.onBridgeStarted(startId = 1, bridge = "first", agent = AgentBridge.SSH))
        assertTrue(tracker.onBridgeStarted(startId = 2, bridge = "second", agent = AgentBridge.SSH))
        assertTrue(tracker.tryReserve(startId = 4))
        assertNull(tracker.onBridgeFinished("first"))
        assertTrue(tracker.tryReserve(startId = 5))
    }

    @Test
    fun `pending reservation prevents an early service stop`() {
        val tracker = AgentBridgeServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 10))
        assertNull(tracker.requestStop(startId = 10))
        assertEquals(10, tracker.onReservationCancelled(startId = 10))
    }

    @Test
    fun `last bridge completion uses latest rejected start id`() {
        val tracker = AgentBridgeServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 20))
        assertTrue(tracker.onBridgeStarted(startId = 20, bridge = "active", agent = AgentBridge.SSH))
        tracker.onStart(startId = 21)

        assertEquals(21, tracker.onBridgeFinished("active"))
    }

    @Test
    fun `destroy drain clears active bridges and pending reservations`() {
        val tracker = AgentBridgeServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 30))
        assertTrue(tracker.onBridgeStarted(startId = 30, bridge = "active", agent = AgentBridge.SSH))
        assertTrue(tracker.tryReserve(startId = 31))

        assertEquals(listOf("active"), tracker.drainActiveBridges())
        assertEquals(31, tracker.requestStop(startId = 31))
    }

    @Test
    fun `active agents follow overlapping bridges`() {
        val tracker = AgentBridgeServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 40))
        assertTrue(tracker.onBridgeStarted(startId = 40, bridge = "ssh-1", agent = AgentBridge.SSH))
        assertTrue(tracker.tryReserve(startId = 41))
        assertTrue(tracker.onBridgeStarted(startId = 41, bridge = "ssh-2", agent = AgentBridge.SSH))
        assertTrue(tracker.tryReserve(startId = 42))
        assertTrue(tracker.onBridgeStarted(startId = 42, bridge = "gpg", agent = AgentBridge.GPG))
        assertEquals(setOf(AgentBridge.SSH, AgentBridge.GPG), tracker.activeAgents())

        assertNull(tracker.onBridgeFinished("gpg"))
        assertEquals(setOf(AgentBridge.SSH), tracker.activeAgents())

        // The other SSH bridge keeps its agent active.
        assertNull(tracker.onBridgeFinished("ssh-1"))
        assertEquals(setOf(AgentBridge.SSH), tracker.activeAgents())

        assertEquals(42, tracker.onBridgeFinished("ssh-2"))
        assertEquals(emptySet(), tracker.activeAgents())
    }

    @Test
    fun `a pending start has no active agent`() {
        val tracker = AgentBridgeServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 50))
        assertEquals(emptySet(), tracker.activeAgents())

        assertTrue(tracker.onBridgeStarted(startId = 50, bridge = "gpg", agent = AgentBridge.GPG))
        tracker.drainActiveBridges()
        assertEquals(emptySet(), tracker.activeAgents())
    }
}
