package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class HistoryRetentionPreferenceTest {
    @Test
    fun `generator history defaults to never for new and existing settings`() = runTest {
        val repository = createRepository()
        assertEquals(Duration.INFINITE, repository.getGeneratorHistoryRetention().first())

        repository.restore(mapOf("agent.history_retention" to 1.days.inWholeMilliseconds)).bind()
        assertEquals(1.days, repository.getAgentHistoryRetention().first())
        assertEquals(Duration.INFINITE, repository.getGeneratorHistoryRetention().first())
    }

    @Test
    fun `each history retains its own period across reload and backup restore`() = runTest {
        val store = JsonKeyValueStore()
        val repository = createRepository(store)
        repository.setAgentHistoryRetention(1.days).bind()
        repository.setGeneratorHistoryRetention(30.days).bind()

        val reloaded = createRepository(store)
        assertEquals(30.days, reloaded.getGeneratorHistoryRetention().first())
        val restored = createRepository()
        restored.restore(reloaded.backup().bind()).bind()
        assertEquals(30.days, restored.getGeneratorHistoryRetention().first())
        assertEquals(1.days, restored.getAgentHistoryRetention().first())

        restored.setGeneratorHistoryRetention(Duration.INFINITE).bind()
        assertEquals(Duration.INFINITE, restored.getGeneratorHistoryRetention().first())
        assertEquals(1.days, restored.getAgentHistoryRetention().first())
    }

    private fun createRepository(store: JsonKeyValueStore = JsonKeyValueStore()) = SettingsRepositoryImpl(
        store = store,
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
