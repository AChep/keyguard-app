package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppLinkWorkerTest {
    private val launcher = TestAppLinkLauncher()

    private val sessions = RecordingSessionLauncher()

    private val linked = AppLinkLive(
        membership = testMembership(label = "Laptop"),
    )

    @Test
    fun `holds the session only while linked to other devices`() = runTest {
        val worker = AppLinkWorker(launcher, sessions).launch(backgroundScope)
        runCurrent()
        assertEquals(emptyList(), sessions.holds)

        launcher.live.value = linked
        runCurrent()
        assertEquals(listOf(P2pGroupId.APP_LINK_ID to linked.membership), sessions.holds)
        assertEquals(1, sessions.held)
        assertEquals(1, launcher.queued)

        launcher.live.value = null
        runCurrent()
        assertEquals(0, sessions.held)

        launcher.live.value = linked
        runCurrent()
        worker.cancelAndJoin()
        // The app left the foreground, or the vault locked.
        assertEquals(0, sessions.held)
        assertEquals(emptyList(), sessions.resends)
    }

    @Test
    fun `a new membership holds the session with it`() = runTest {
        AppLinkWorker(launcher, sessions).launch(backgroundScope)
        launcher.live.value = linked
        runCurrent()

        val renamed = linked.copy(membership = testMembership(label = "Work laptop"))
        launcher.live.value = renamed
        runCurrent()
        assertEquals(listOf(linked.membership, renamed.membership), sessions.holds.map { it.second })
        assertEquals(1, sessions.held)
        // The registry starts the session over for the new membership itself.
        assertEquals(emptyList(), sessions.resends)
    }

    private fun testMembership(label: String) = P2pMembership(
        groupId = "group",
        groupKeyBase64 = "group-key",
        founderId = "device",
        device = P2pDevice(
            deviceId = "device",
            label = label,
            platform = "desktop",
            noisePublicKeyBase64 = "noise-public",
            noisePrivateKeyBase64 = "noise-private",
            kemPublicKeyBase64 = "kem-public",
            kemPrivateKeyBase64 = "kem-private",
            signPublicKeyBase64 = "sign-public",
            signPrivateKeyBase64 = "sign-private",
        ),
    )
}

private class TestAppLinkLauncher : AppLinkLauncher {
    val live = MutableStateFlow<AppLinkLive?>(null)

    /** How many sync passes were queued. */
    var queued = 0
        private set

    override fun live(): Flow<AppLinkLive?> = live

    override fun queueSync(): IO<Unit> = ioEffect { queued += 1 }
}

private class RecordingSessionLauncher : KeyguardSessionLauncher {
    /** The group id and membership of every hold. */
    val holds = mutableListOf<Pair<String, P2pMembership>>()

    /** How many holds last now. */
    var held = 0
        private set

    /** The group id and peers of every resend request. */
    val resends = mutableListOf<Pair<String, Set<String>?>>()

    override suspend fun hold(
        groupId: String,
        membership: P2pMembership,
    ): Nothing {
        holds += groupId to membership
        held += 1
        try {
            awaitCancellation()
        } finally {
            held -= 1
        }
    }

    override suspend fun requestResend(groupId: String, peers: Set<String>?) {
        resends += groupId to peers
    }
}
