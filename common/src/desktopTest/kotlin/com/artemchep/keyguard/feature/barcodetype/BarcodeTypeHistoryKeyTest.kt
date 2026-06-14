package com.artemchep.keyguard.feature.barcodetype

import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BarcodeTypeHistoryKeyTest {
    private val cryptoGenerator = CryptoGeneratorJvm()

    @Test
    fun `creates stable md5 key from cipher id and value`() {
        val key = createBarcodeTypeHistoryKey(
            cryptoGenerator = cryptoGenerator,
            cipherLocalId = "cipher-local-id",
            value = "value shown",
        )

        assertEquals("b833bea1888a5cc7678605ee7236dd80", key)
    }

    @Test
    fun `creates different keys for different cipher value pairs`() {
        val key1 = createBarcodeTypeHistoryKey(
            cryptoGenerator = cryptoGenerator,
            cipherLocalId = "cipher-local-id",
            value = "value one",
        )
        val key2 = createBarcodeTypeHistoryKey(
            cryptoGenerator = cryptoGenerator,
            cipherLocalId = "cipher-local-id",
            value = "value two",
        )
        val key3 = createBarcodeTypeHistoryKey(
            cryptoGenerator = cryptoGenerator,
            cipherLocalId = "cipher-local-id-2",
            value = "value one",
        )

        assertNotEquals(key1, key2)
        assertNotEquals(key1, key3)
    }
}
