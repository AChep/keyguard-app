package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptionWarning
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpDecryptionWarning
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeGpgWriteWarningTest {
    @Test
    fun `decryption warnings map to the shared service model`() {
        assertEquals(
            GpgOpenPgpDecryptionWarning.WEAK_RSA_KEY,
            NativeOpenPgpDecryptionWarning.WEAK_RSA_KEY.toDomain(),
        )
        assertEquals(
            GpgOpenPgpDecryptionWarning.ELGAMAL_KEY,
            NativeOpenPgpDecryptionWarning.ELGAMAL_KEY.toDomain(),
        )
    }
}
