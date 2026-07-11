package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class SettingsRepositoryAgentApprovalConfigTest {
    @Test
    fun `policy and window writes publish versioned config before returning`() = runTest {
        val repository = createRepository()
        val config = repository.getSshAgentApprovalCacheConfig()
        val initial = config.get()

        repository
            .setSshAgentApprovalCachePolicy(AgentApprovalCachePolicy.Connection)
            .bind()
        val afterPolicy = config.get()

        assertEquals(AgentApprovalCachePolicy.Connection, afterPolicy.cachePolicy)
        assertEquals(initial.revision + 1L, afterPolicy.revision)

        repository
            .setSshAgentApprovalWindow(Duration.ZERO)
            .bind()
        val afterWindow = config.get()

        assertEquals(Duration.ZERO, afterWindow.approvalWindow)
        assertEquals(afterPolicy.revision + 1L, afterWindow.revision)
    }

    @Test
    fun `settings restore advances the same approval config revision`() = runTest {
        val repository = createRepository()
        val config = repository.getSshAgentApprovalCacheConfig()
        val initial = config.get()

        repository.restore(
            mapOf(
                "ssh_agent.approval_window" to 0L,
                "ssh_agent.approval_cache_policy" to AgentApprovalCachePolicy.Connection.storageKey,
            ),
        ).bind()
        val restored = config.get()

        assertEquals(Duration.ZERO, restored.approvalWindow)
        assertEquals(AgentApprovalCachePolicy.Connection, restored.cachePolicy)
        assertEquals(initial.revision + 2L, restored.revision)
    }

    private fun createRepository() = SettingsRepositoryImpl(
        store = JsonKeyValueStore(),
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
