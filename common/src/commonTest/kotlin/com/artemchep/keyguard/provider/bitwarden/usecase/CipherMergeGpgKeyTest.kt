package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class CipherMergeGpgKeyTest {
    @Test
    fun `merge never synthesizes a gpg key from independent fields`() {
        val firstKey =
            DSecret.GpgKey(
                privateKeyArmored = "private-a",
                publicKeyArmored = "public-a",
                fingerprint = "fingerprint-b",
            )
        val secondKey =
            DSecret.GpgKey(
                privateKeyArmored = "private-a",
                publicKeyArmored = "public-b",
                fingerprint = "fingerprint-a",
            )
        val thirdKey =
            DSecret.GpgKey(
                privateKeyArmored = "private-b",
                publicKeyArmored = "public-a",
                fingerprint = "fingerprint-a",
            )
        val inputs =
            listOf(firstKey, secondKey, thirdKey)
                .mapIndexed { index, key ->
                    createSecret(id = "gpg-$index").copy(gpgKey = key)
                }

        val merged = CipherMergeImpl()(inputs)

        assertEquals(firstKey, merged.gpgKey)
        assertEquals(true, merged.gpgKey in listOf(firstKey, secondKey, thirdKey))
    }
}
