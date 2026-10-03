package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavTransactionsPreferenceTest {
    @Test
    fun `defaults on and preserves restored false`() = runTest {
        val repository = createRepository()
        assertTrue(repository.getWebDavTransactions().first())
        repository.restore(mapOf("webdav_transactions" to false)).bind()
        assertFalse(repository.getWebDavTransactions().first())
    }

    @Test
    fun `is part of the exported settings`() = runTest {
        val repository = createRepository()
        repository.setWebDavTransactions(false).bind()
        val prefs = repository.getPrefs(includeInternalPrefs = false)
        assertTrue(prefs.any { it.key == "webdav_transactions" })
    }

    private fun createRepository() = SettingsRepositoryImpl(
        store = JsonKeyValueStore(),
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
