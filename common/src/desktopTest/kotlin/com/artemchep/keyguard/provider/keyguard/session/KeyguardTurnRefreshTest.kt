@file:OptIn(ExperimentalCoroutinesApi::class)

package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.feature.p2psync.transport.IceServer
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.provider.keyguard.transport.IceServersGrant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When a session over TURN moves to a new connection, on the virtual clock of the test. */
class KeyguardTurnRefreshTest {
    private val turn = listOf(IceServer(urls = listOf("turn:turn.test:3478"), username = "u", credential = "c"))

    private val stun = listOf(IceServer(urls = listOf("stun:stun.test:3478")))

    /** Servers the relay issued at [issuedAt], for [TTL_MS], on the clock of the test. */
    private fun TestScope.grant(
        servers: List<IceServer> = turn,
        issuedAt: Long = currentTime,
    ) = IceServersGrant(servers, issuedAtMs = issuedAt, ttlMs = TTL_MS, nowMs = { currentTime })

    /** Runs the refresh of a session, recording the times it reconnects. */
    private fun TestScope.refresh(
        kind: TransportKind,
        grant: IceServersGrant,
        overTurn: MutableStateFlow<Boolean>,
    ): List<Long> {
        val reconnects = mutableListOf<Long>()
        backgroundScope.launch {
            reconnectBeforeTurnExpires(kind, grant, overTurn) { reconnects += currentTime }
        }
        runCurrent()
        return reconnects
    }

    @Test
    fun `a session over TURN reconnects at 90 percent of the lifetime of its credentials`() = runTest {
        // From the cache: issued well before the connection opened.
        val grant = grant()
        advanceTimeBy(1_000_000L)
        val reconnects = refresh(TransportKind.WEBRTC, grant, MutableStateFlow(true))

        advanceTimeBy(REFRESH_AT - 1L - currentTime)
        runCurrent()
        assertEquals(emptyList(), reconnects)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(REFRESH_AT), reconnects)
        // Well before the credentials expire.
        assertTrue(reconnects.single() < TTL_MS)
    }

    @Test
    fun `a direct path never reconnects`() = runTest {
        val reconnects = refresh(TransportKind.WEBRTC, grant(), MutableStateFlow(false))
        advanceTimeBy(10 * TTL_MS)
        assertEquals(emptyList(), reconnects)
    }

    @Test
    fun `relay forwarding, or a connection without TURN credentials, never reconnects`() = runTest {
        val relayed = refresh(TransportKind.RELAY, grant(), MutableStateFlow(true))
        val withoutTurn = refresh(TransportKind.WEBRTC, grant(servers = stun), MutableStateFlow(true))
        advanceTimeBy(10 * TTL_MS)
        assertEquals(emptyList(), relayed)
        assertEquals(emptyList(), withoutTurn)
    }

    @Test
    fun `a path that leaves TURN before its time does not reconnect until it is back on TURN`() = runTest {
        val overTurn = MutableStateFlow(true)
        val reconnects = refresh(TransportKind.WEBRTC, grant(), overTurn)
        advanceTimeBy(REFRESH_AT / 2)
        overTurn.value = false
        advanceTimeBy(TTL_MS)
        assertEquals(emptyList(), reconnects)

        // Its credentials are past their time: at once.
        overTurn.value = true
        runCurrent()
        assertEquals(listOf(REFRESH_AT / 2 + TTL_MS), reconnects)
    }

    private companion object {
        /** 2 hours, as the relay issues them. */
        const val TTL_MS: Long = 2L * 60L * 60L * 1_000L

        /** 90% of [TTL_MS] after the credentials were issued, at 0. */
        const val REFRESH_AT: Long = TTL_MS * 9L / 10L
    }
}
