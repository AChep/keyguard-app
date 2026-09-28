package com.artemchep.keyguard.feature.datasafety

import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataSafetyCatalogTest {
    @Test
    fun `settings encryption reflects each storage backend`() {
        val android = Platform.Mobile.Android(isChromebook = false, isWatch = false, sdk = 35)
        val platforms = listOf(
            android,
            Platform.Mobile.Ios.Native,
            Platform.Desktop.MacOS.Native,
            Platform.Desktop.MacOS.Jvm,
            Platform.Desktop.Linux.native,
            Platform.Desktop.Windows,
        )
        platforms.forEach { platform ->
            val encryption = catalog(platform).filterIsInstance<DataSafetyItem.Row>()
                .single { it.key == "local.settings.encryption" }.value
            assertEquals(if (platform == android) "AES" else "none", encryption, platform.toString())
        }
    }

    @Test
    fun `vault description includes legacy KDF support and the salt size in bits`() {
        val items = catalog(Platform.Mobile.Ios.Native)
        val rows = items.filterIsInstance<DataSafetyItem.Row>().associateBy { it.key }
        assertEquals("512", rows.getValue("local.vault.algorithm.salt").value)
        assertEquals("KDF(password, salt)", rows.getValue("local.vault.algorithm.hash").value)
        assertEquals("KDF(password, hash)", rows.getValue("local.vault.algorithm.key").value)
        assertTrue(items.filterIsInstance<DataSafetyItem.Text>().any { it.text == "KDF versions" })
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `learn more is absent on platforms without a browser`() {
        val watch = Platform.Mobile.Android(isChromebook = false, isWatch = true, sdk = 35)
        assertFalse(catalog(watch).any { it is DataSafetyItem.LearnMore })
        assertTrue(catalog(Platform.Mobile.Ios.Native).any { it is DataSafetyItem.LearnMore })
    }

    private fun catalog(platform: Platform) = dataSafetyCatalog(
        platform = platform,
        text = { resource ->
            when (resource) {
                Res.string.encryption_algorithm_256bit_aes -> "AES"
                Res.string.none -> "none"
                Res.string.app_password -> "password"
                Res.string.encryption_salt -> "salt"
                Res.string.encryption_hash -> "hash"
                Res.string.datasafety_local_kdf_versions_note -> "KDF versions"
                else -> "localized text"
            }
        },
        format = { _, argument -> argument.toString() },
    )
}
