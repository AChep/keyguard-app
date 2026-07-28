package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgp
import com.artemchep.keyguard.nativecrypto.NativeCryptoSsh
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the user-facing RSA key size options against drifting away from the
 * sizes the native crypto layer accepts. The native validation stays
 * independent on purpose; these tests only prove that every size offered in
 * the UI is one the native generator will actually take.
 */
class NativeRsaKeySizeConsistencyTest {
    @Test
    fun sshRsaLengthOptionsAreSupportedByTheNativeGenerator() {
        KeyPairGenerator.RsaLength.entries.forEach { length ->
            assertTrue(
                length.size in NativeCryptoSsh.SUPPORTED_RSA_KEY_BITS,
                "SSH RSA option $length offers ${length.size} bits, which the " +
                    "native SSH key generator does not support",
            )
        }
    }

    @Test
    fun gpgRsaLengthOptionsAreSupportedByTheNativeGenerator() {
        GpgKeyConfig.RsaLength.entries.forEach { length ->
            assertTrue(
                length.size in NativeCryptoOpenPgp.SUPPORTED_RSA_KEY_BITS,
                "GPG RSA option $length offers ${length.size} bits, which the " +
                    "native OpenPGP key generator does not support",
            )
        }
    }
}
