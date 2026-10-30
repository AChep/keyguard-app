package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_PROMPT_TIMEOUT
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SettingsRepositoryAgentApprovalTimeoutTest {
    @Test
    fun `every agent defaults to the prompt timeout`() = runTest {
        val repository = createRepository()
        assertEquals(AGENT_APPROVAL_PROMPT_TIMEOUT, repository.getSshAgentApprovalTimeout().first())
        assertEquals(AGENT_APPROVAL_PROMPT_TIMEOUT, repository.getGpgAgentApprovalTimeout().first())
        assertEquals(AGENT_APPROVAL_PROMPT_TIMEOUT, repository.getEnvironmentsMcpApprovalTimeout().first())
        assertEquals(AGENT_APPROVAL_PROMPT_TIMEOUT, repository.getCliApprovalTimeout().first())
    }

    @Test
    fun `each agent persists its own timeout`() = runTest {
        val store = JsonKeyValueStore()
        createRepository(store).apply {
            setSshAgentApprovalTimeout(Duration.INFINITE).bind()
            setGpgAgentApprovalTimeout(30.minutes).bind()
            setEnvironmentsMcpApprovalTimeout(5.minutes).bind()
        }

        val reloaded = createRepository(store)
        assertEquals(Duration.INFINITE, reloaded.getSshAgentApprovalTimeout().first())
        assertEquals(30.minutes, reloaded.getGpgAgentApprovalTimeout().first())
        assertEquals(5.minutes, reloaded.getEnvironmentsMcpApprovalTimeout().first())
        assertEquals(AGENT_APPROVAL_PROMPT_TIMEOUT, reloaded.getCliApprovalTimeout().first())
    }

    private fun createRepository(
        store: JsonKeyValueStore = JsonKeyValueStore(),
    ) = SettingsRepositoryImpl(
        store = store,
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
