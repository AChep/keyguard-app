package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The same reference vectors must cross every platform bridge unchanged. */
class NativeCryptoKdfTest {
    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        assertContentEquals(
            ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865").hexToByteArray(),
            NativeCrypto.primitives.hkdfSha256(
                seed = ByteArray(22) { 0x0b },
                salt = "000102030405060708090a0b0c".hexToByteArray(),
                info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray(),
                length = 42,
            ),
        )
    }

    @Test
    fun pbkdf2Sha256MatchesKnownVector() {
        assertContentEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b".hexToByteArray(),
            NativeCrypto.primitives.pbkdf2Sha256(
                seed = "password".encodeToByteArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 1,
                length = 32,
            ),
        )
    }

    @Test
    fun argon2ModesMatchExistingNativeGoldens() {
        val cases = listOf(
            NativeArgon2Mode.ARGON2_I to "b9c401d1844a67d50eae3967dc28870b22e508092e861a37",
            NativeArgon2Mode.ARGON2_D to "8727405fd07c32c78d64f547f24150d3f2e703a89f981a19",
            NativeArgon2Mode.ARGON2_ID to "655ad15eac652dc59f7170a7332bf49b8469be1fdb9c28bb",
        )
        cases.forEach { (mode, expected) ->
            assertContentEquals(
                expected.hexToByteArray(),
                NativeCrypto.primitives.argon2(
                    mode = mode,
                    seed = "password".encodeToByteArray(),
                    salt = "somesalt".encodeToByteArray(),
                    iterations = 1,
                    memoryKb = 64,
                    parallelism = 1,
                    length = 24,
                ),
            )
        }
    }
}
