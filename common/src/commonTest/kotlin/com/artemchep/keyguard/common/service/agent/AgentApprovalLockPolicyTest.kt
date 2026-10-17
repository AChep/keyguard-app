package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.VaultSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApprovalLockPolicyTest {
    private fun unlocked(id: String) = MasterSession.Key(
        masterKey = MasterKey(MasterKdfVersion.V0, ByteArray(32)),
        session = object : VaultSession {
            override val id = id
            override val active = MutableStateFlow(true)
            override fun retire() = Unit
            override fun close() = Unit
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.fromEpochSeconds(0),
    )

    @Test
    fun `a lock denies the pending prompts`() = runTest {
        val broker = AgentApprovalBroker<String>(idOf = { it })
        val sessions = MutableStateFlow<MasterSession>(unlocked("a"))
        backgroundScope.launch { broker.denyWhenSessionEnds(sessions) }
        val pending = async { broker.request { "prompt" } }
        runCurrent()

        sessions.value = MasterSession.Empty()
        runCurrent()
        assertEquals(AgentApprovalBroker.Result.Denied, pending.await())
    }

    @Test
    fun `a replaced session denies the pending prompts`() = runTest {
        val broker = AgentApprovalBroker<String>(idOf = { it })
        val sessions = MutableStateFlow<MasterSession>(unlocked("a"))
        backgroundScope.launch { broker.denyWhenSessionEnds(sessions) }
        val pending = async { broker.request { "prompt" } }
        runCurrent()

        sessions.value = unlocked("b")
        runCurrent()
        assertEquals(AgentApprovalBroker.Result.Denied, pending.await())
    }

    @Test
    fun `a prompt shown while locked survives the unlock`() = runTest {
        val broker = AgentApprovalBroker<String>(idOf = { it })
        val sessions = MutableStateFlow<MasterSession>(MasterSession.Empty())
        backgroundScope.launch { broker.denyWhenSessionEnds(sessions) }
        val pending = async { broker.request { "prompt" } }
        runCurrent()

        sessions.value = unlocked("a")
        runCurrent()
        assertFalse(pending.isCompleted, "The user unlocks to answer the prompt")
        assertEquals(listOf("prompt"), broker.pending.value)
        broker.respond("prompt", true)
        assertEquals(AgentApprovalBroker.Result.Approved, pending.await())
    }
}
