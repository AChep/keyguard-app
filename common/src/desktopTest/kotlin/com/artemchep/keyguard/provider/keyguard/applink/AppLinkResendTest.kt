package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * When a sync pass asks a peer for its roster and envelope again, see
 * [KeyguardSessionLauncher.requestResend]: a pass that failed, and writes that were too far
 * in the future until now. A category turned on again asks too, see [PutAppLinkSyncCategoryImpl].
 */
class AppLinkResendTest {
    private val sessions = RecordingResends()

    @Test
    fun `a pass that fails asks its senders again and takes their state once it arrives`() = runTest {
        val (a, b) = linkedPair()
        a.addForwarder("Work")
        b.dbManager.failNext(TAG, IllegalStateException("The disk is full"))

        assertFails { b.receive(a) }
        assertEquals(listOf(GROUP_ID to setOf(a.deviceId)), sessions.requests.value)
        // What the pass took is dropped: the peer sends it again.
        assertTrue(b.components.inbox.peek(GROUP_ID).isEmpty)
        assertTrue(b.forwarderList().isEmpty())

        b.receive(a)
        assertEquals(listOf("Work"), b.forwarderList().map { it.name })
        assertEquals(1, sessions.requests.value.size)
    }

    @Test
    fun `writes too far in the future are asked for once, when they are due`() = runTest {
        val (a, b) = linkedPair()
        // The clock of A runs ahead of the one of B by more than the drift allows.
        a.time = b.time + HlcClock.MAX_FUTURE_DRIFT_MS + DUE_IN_MS
        a.addForwarder("Ahead")
        b.receive(a)
        assertTrue(b.forwarderList().isEmpty())
        assertEquals(emptyList(), sessions.requests.value)

        val requests = withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { sessions.requests.first { it.isNotEmpty() } }
        }
        assertEquals(listOf(GROUP_ID to setOf(a.deviceId)), requests)
        // By then the writes are taken.
        b.time += DUE_IN_MS
        b.receive(a)
        assertEquals(listOf("Ahead"), b.forwarderList().map { it.name })
        withContext(Dispatchers.Default) { delay(3 * DUE_IN_MS) }
        assertEquals(1, sessions.requests.value.size)
    }

    @Test
    fun `a category turned on again asks the peers again`() = runTest {
        AppLinkTestDevice("A").use { a ->
            val put = PutAppLinkSyncCategoryImpl(
                switches = a.switches,
                queueSync = { ioUnit() },
                sessions = { sessions },
            )
            // Turned off: the peers have nothing to send again.
            put(DAppLinkSyncCategory.EMAIL_FORWARDERS, false).bind()
            assertEquals(emptyList(), sessions.requests.value)

            put(DAppLinkSyncCategory.EMAIL_FORWARDERS, true).bind()
            assertEquals(listOf<Pair<String, Set<String>?>>(GROUP_ID to null), sessions.requests.value)
        }
    }

    /** Two apps: A starts a link and B, whose passes ask [sessions] for resends, joins it. */
    private suspend fun linkedPair(): Pair<AppLinkTestDevice, AppLinkTestDevice> {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B", sessionLauncher = sessions)
        a.link()
        b.time = a.time + 1
        b.join(a)
        return a to b
    }

    private companion object {
        const val GROUP_ID = P2pGroupId.APP_LINK_ID

        /** The tag of the changes of a sync pass, see [AppLinkSync]. */
        const val TAG = "AppLinkSync"

        /** How long after the first pass the writes of A are due. */
        const val DUE_IN_MS = 300L
    }
}

/** TEST ONLY: records the resend requests of the sync passes; holds nothing. */
private class RecordingResends : KeyguardSessionLauncher {
    /** The group id and peers of every request. */
    val requests = MutableStateFlow<List<Pair<String, Set<String>?>>>(emptyList())

    override suspend fun hold(groupId: String, membership: P2pMembership): Nothing = awaitCancellation()

    override suspend fun requestResend(groupId: String, peers: Set<String>?) {
        requests.update { it + (groupId to peers) }
    }
}
