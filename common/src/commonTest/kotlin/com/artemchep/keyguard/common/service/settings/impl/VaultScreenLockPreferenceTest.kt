package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultPersistImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultScreenLockPreferenceTest {
    @Test
    fun `existing preference defaults on and preserves restored false`() = runTest {
        val repository = createRepository()
        assertTrue(repository.getVaultScreenLock().first())
        repository.restore(mapOf("vault_screen_lock" to false)).bind()
        assertFalse(repository.getVaultScreenLock().first())
    }

    @Test
    fun `persisted vault key suppresses locking without overwriting the preference`() = runTest {
        val repository = createRepository()
        val getLock = GetVaultLockAfterScreenOffImpl(repository, GetVaultPersistImpl(repository))
        assertTrue(getLock().first())
        repository.setVaultPersist(true).bind()
        assertFalse(getLock().first())
        assertTrue(repository.getVaultScreenLock().first())
        repository.setVaultPersist(false).bind()
        assertTrue(getLock().first())
    }

    private fun createRepository() = SettingsRepositoryImpl(
        store = JsonKeyValueStore(),
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
