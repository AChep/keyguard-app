package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgp
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeBridgeUtilsTest {
    @Test
    fun `OpenPGP key list clamp preserves the first documents`() {
        val limit = NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST
        val documents = List(limit + 2) { index -> index }

        val clamped = documents.clampToNativeOpenPgpKeyLimit()

        assertEquals(limit, clamped.size)
        assertEquals((0 until limit).toList(), clamped)
    }

    @Test
    fun `OpenPGP key list clamp preserves lists within the limit`() {
        val documents = listOf(1, 2, 3)

        assertEquals(documents, documents.clampToNativeOpenPgpKeyLimit())
    }
}
