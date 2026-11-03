package com.artemchep.keyguard.common.service.sshagent.bookmarks

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.passkey.FakeVaultSessions
import com.artemchep.keyguard.common.service.session.testMasterSessionKey
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SshBookmarksConfigSyncerTest {
    private val unlocked = testMasterSessionKey()

    private val identity = SshBookmarkIdentity(
        keySha256 = "a".repeat(64),
        publicKey = "ssh-ed25519 AAAAkey",
    )

    @Test
    fun `writes while unlocked, keeps while locked and clears once off`() = runTest {
        val store = RecordingSshBookmarksConfigStore()
        val sessions = FakeVaultSessions(unlocked)
        val sshAgent = MutableStateFlow(true)
        val enabled = MutableStateFlow(true)
        val bookmarks = MutableStateFlow(bookmarks("one.example.com"))
        startSyncer(
            store = store,
            getVaultSession = sessions,
            sshAgent = sshAgent,
            enabled = enabled,
            bookmarks = bookmarks,
        )

        runCurrent()
        assertEquals(listOf("one.example.com"), store.hosts())

        bookmarks.value = bookmarks("two.example.com")
        runCurrent()
        assertEquals(listOf("two.example.com"), store.hosts())

        // The agent still offers the keys.
        sessions.state.value = MasterSession.Empty()
        runCurrent()
        assertEquals(listOf("two.example.com"), store.hosts())

        sshAgent.value = false
        runCurrent()
        assertNull(store.config)

        // Nothing to write until the vault is unlocked.
        sshAgent.value = true
        runCurrent()
        assertNull(store.config)

        sessions.state.value = unlocked
        runCurrent()
        assertEquals(listOf("two.example.com"), store.hosts())

        enabled.value = false
        runCurrent()
        assertNull(store.config)
    }

    @Test
    fun `adds the agent once its socket resolves`() = runTest {
        val store = RecordingSshBookmarksConfigStore()
        var calls = 0
        startSyncer(
            store = store,
            bookmarks = MutableStateFlow(bookmarks("one.example.com")),
            socketPath = {
                calls += 1
                "/tmp/agent.sock".takeIf { calls > 1 }
            },
        )

        runCurrent()
        assertEquals(listOf("one.example.com"), store.hosts())
        assertFalse(store.hasIdentityAgent())

        advanceTimeBy(31.seconds)
        runCurrent()
        assertTrue(store.hasIdentityAgent())

        // A resolved socket is final.
        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `keeps syncing after a failed write`() = runTest {
        val store = RecordingSshBookmarksConfigStore(failures = 1)
        val bookmarks = MutableStateFlow(bookmarks("one.example.com"))
        startSyncer(
            store = store,
            bookmarks = bookmarks,
        )

        runCurrent()
        assertNull(store.config)

        bookmarks.value = bookmarks("two.example.com")
        runCurrent()
        assertEquals(listOf("two.example.com"), store.hosts())
    }

    private fun TestScope.startSyncer(
        store: SshBookmarksConfigStore,
        getVaultSession: FakeVaultSessions = FakeVaultSessions(unlocked),
        sshAgent: Flow<Boolean> = MutableStateFlow(true),
        enabled: Flow<Boolean> = MutableStateFlow(true),
        bookmarks: Flow<SshBookmarks>,
        socketPath: suspend () -> String? = { "/tmp/agent.sock" },
    ) = testSshBookmarksConfigSyncer(
        store = store,
        getVaultSession = getVaultSession,
        sshAgent = sshAgent,
        enabled = enabled,
        bookmarks = bookmarks,
        socketPath = socketPath,
    ).start(backgroundScope, flowOf(LeLifecycleState.RESUMED))

    private fun bookmarks(host: String) = SshBookmarks(
        entries = listOf(
            SshBookmarkEntry(
                bookmark = SshBookmark(host = host, user = null, port = null),
                identity = identity,
                sortKey = host,
            ),
        ),
        skipped = 0,
    )
}
