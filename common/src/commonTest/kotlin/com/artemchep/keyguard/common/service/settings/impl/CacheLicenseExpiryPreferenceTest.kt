package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CacheLicenseExpiryPreferenceTest {
    @Test
    fun `defaults to no license and round trips every expiry`() = runTest {
        val repository = createRepository()
        assertNull(repository.getCacheLicenseExpiry().first())
        listOf(
            LicenseEntitlement.Expiry.Lifetime,
            LicenseEntitlement.Expiry.PaidThrough("2027-03"),
            null,
        ).forEach { expiry ->
            repository.setCacheLicenseExpiry(expiry).bind()
            assertEquals(expiry, repository.getCacheLicenseExpiry().first())
        }
    }

    @Test
    fun `is not part of the exported settings`() = runTest {
        val repository = createRepository()
        repository.setCacheLicenseExpiry(LicenseEntitlement.Expiry.Lifetime).bind()
        val prefs = repository.getPrefs(includeInternalPrefs = false)
        assertFalse(prefs.any { it.key == "cache_license_expiry" })
    }

    private fun createRepository() = SettingsRepositoryImpl(
        store = JsonKeyValueStore(),
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
