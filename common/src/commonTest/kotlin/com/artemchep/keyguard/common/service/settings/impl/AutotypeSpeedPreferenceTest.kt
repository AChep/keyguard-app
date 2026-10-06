package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AutotypeSpeed
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AutotypeSpeedPreferenceTest {
    @Test
    fun `defaults to fast and falls back for unknown stored values`() = runTest {
        val repository = createRepository()
        assertEquals(AutotypeSpeed.Fast, repository.getAutotypeSpeed().first())
        repository.setAutotypeSpeed(AutotypeSpeed.Slow).bind()
        repository.restore(mapOf("autotype_speed" to "unknown")).bind()
        assertEquals(AutotypeSpeed.Fast, repository.getAutotypeSpeed().first())
    }

    @Test
    fun `each speed persists across repository recreation and backup restore`() = runTest {
        val store = JsonKeyValueStore()
        val repository = createRepository(store)
        for (speed in AutotypeSpeed.entries) {
            repository.setAutotypeSpeed(speed).bind()
            assertEquals(speed, createRepository(store).getAutotypeSpeed().first())

            val backup = repository.backup().bind()
            assertEquals(speed.storageKey, backup["autotype_speed"])
            val restored = createRepository()
            restored.restore(backup).bind()
            assertEquals(speed, restored.getAutotypeSpeed().first())
        }
    }

    private fun createRepository(store: JsonKeyValueStore = JsonKeyValueStore()) = SettingsRepositoryImpl(
        store = store,
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
