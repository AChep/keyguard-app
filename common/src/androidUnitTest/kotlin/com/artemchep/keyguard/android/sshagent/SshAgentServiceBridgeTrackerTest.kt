package com.artemchep.keyguard.android.sshagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshAgentServiceBridgeTrackerTest {
    @Test
    fun `duplicate pending starts are rejected without limiting active bridges`() {
        val tracker = SshAgentServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 1))
        assertTrue(tracker.tryReserve(startId = 2))
        assertFalse(tracker.tryReserve(startId = 2))

        assertTrue(tracker.onBridgeStarted(startId = 1, bridge = "first"))
        assertTrue(tracker.onBridgeStarted(startId = 2, bridge = "second"))
        assertTrue(tracker.tryReserve(startId = 4))
        assertNull(tracker.onBridgeFinished("first"))
        assertTrue(tracker.tryReserve(startId = 5))
    }

    @Test
    fun `pending reservation prevents an early service stop`() {
        val tracker = SshAgentServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 10))
        assertNull(tracker.requestStop(startId = 10))
        assertEquals(10, tracker.onReservationCancelled(startId = 10))
    }

    @Test
    fun `last bridge completion uses latest rejected start id`() {
        val tracker = SshAgentServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 20))
        assertTrue(tracker.onBridgeStarted(startId = 20, bridge = "active"))
        tracker.onStart(startId = 21)

        assertEquals(21, tracker.onBridgeFinished("active"))
    }

    @Test
    fun `destroy drain clears active bridges and pending reservations`() {
        val tracker = SshAgentServiceBridgeTracker<String>()

        assertTrue(tracker.tryReserve(startId = 30))
        assertTrue(tracker.onBridgeStarted(startId = 30, bridge = "active"))
        assertTrue(tracker.tryReserve(startId = 31))

        assertEquals(listOf("active"), tracker.drainActiveBridges())
        assertEquals(31, tracker.requestStop(startId = 31))
    }
}
