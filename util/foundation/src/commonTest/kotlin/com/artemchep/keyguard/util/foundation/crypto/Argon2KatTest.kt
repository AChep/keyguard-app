package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Argon2 v1.3 known-answer tests using the official PHC reference vectors.
 *
 * This suite is ALSO the regression gate for cross-backend Argon2 equivalence
 * (BouncyCastle on JVM vs diglol on iOS) and for the diglol cinterop. If the
 * diglol dependency is bumped, re-verify these vectors to ensure all backends
 * still produce identical output.
 *
 * Only small parameter sets are exercised here (no heavy m >= 65536 cases) so
 * the suite stays fast enough to run on every build.
 */
class Argon2KatTest {
    private val crypto = PlatformCryptoPrimitives()

    private val password = "password".encodeToByteArray()
    private val salt = "somesalt".encodeToByteArray()

    private fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int,
    ): String =
        crypto.argon2(
            mode = mode,
            seed = seed,
            salt = salt,
            iterations = iterations,
            memoryKb = memoryKb,
            parallelism = parallelism,
            length = length,
        ).toHex()

    // region Argon2i
    @Test
    fun argon2i_t1_m64_p1() {
        val expectedHex = "b9c401d1844a67d50eae3967dc28870b22e508092e861a37"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_I,
                seed = password,
                iterations = 1,
                memoryKb = 64,
                parallelism = 1,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2i_t2_m64_p1() {
        val expectedHex = "8cf3d8f76a6617afe35fac48eb0b7433a9a670ca4a07ed64"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_I,
                seed = password,
                iterations = 2,
                memoryKb = 64,
                parallelism = 1,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2i_t2_m64_p2() {
        val expectedHex = "2089f3e78a799720f80af806553128f29b132cafe40d059f"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_I,
                seed = password,
                iterations = 2,
                memoryKb = 64,
                parallelism = 2,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2i_t3_m256_p2() {
        val expectedHex = "f5bbf5d4c3836af13193053155b73ec7476a6a2eb93fd5e6"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_I,
                seed = password,
                iterations = 3,
                memoryKb = 256,
                parallelism = 2,
                length = 24,
            ),
        )
    }
    // endregion

    // region Argon2d
    @Test
    fun argon2d_t1_m64_p1() {
        val expectedHex = "8727405fd07c32c78d64f547f24150d3f2e703a89f981a19"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_D,
                seed = password,
                iterations = 1,
                memoryKb = 64,
                parallelism = 1,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2d_t2_m64_p1() {
        val expectedHex = "3be9ec79a69b75d3752acb59a1fbb8b295a46529c48fbb75"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_D,
                seed = password,
                iterations = 2,
                memoryKb = 64,
                parallelism = 1,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2d_t2_m64_p2() {
        val expectedHex = "68e2462c98b8bc6bb60ec68db418ae2c9ed24fc6748a40e9"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_D,
                seed = password,
                iterations = 2,
                memoryKb = 64,
                parallelism = 2,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2d_t3_m256_p2() {
        val expectedHex = "f4f0669218eaf3641f39cc97efb915721102f4b128211ef2"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_D,
                seed = password,
                iterations = 3,
                memoryKb = 256,
                parallelism = 2,
                length = 24,
            ),
        )
    }
    // endregion

    // region Argon2id
    @Test
    fun argon2id_t1_m64_p1() {
        val expectedHex = "655ad15eac652dc59f7170a7332bf49b8469be1fdb9c28bb"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = password,
                iterations = 1,
                memoryKb = 64,
                parallelism = 1,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2id_t2_m64_p1() {
        val expectedHex = "068d62b26455936aa6ebe60060b0a65870dbfa3ddf8d41f7"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = password,
                iterations = 2,
                memoryKb = 64,
                parallelism = 1,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2id_t2_m64_p2() {
        val expectedHex = "350ac37222f436ccb5c0972f1ebd3bf6b958bf2071841362"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = password,
                iterations = 2,
                memoryKb = 64,
                parallelism = 2,
                length = 24,
            ),
        )
    }

    @Test
    fun argon2id_t3_m256_p2() {
        val expectedHex = "4668d30ac4187e6878eedeacf0fd83c5a0a30db2cc16ef0b"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = password,
                iterations = 3,
                memoryKb = 256,
                parallelism = 2,
                length = 24,
            ),
        )
    }
    // endregion

    // region Empty password
    @Test
    fun argon2i_emptyPassword_t2_m64_p2() {
        val expectedHex = "105ba0b3e28d378ac97930f562f44aaf7c2d5ff401e9242d75fc3a3467d1cdc7"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_I,
                seed = ByteArray(0),
                iterations = 2,
                memoryKb = 64,
                parallelism = 2,
                length = 32,
            ),
        )
    }

    @Test
    fun argon2d_emptyPassword_t2_m64_p2() {
        val expectedHex = "027ad398cc43b2688c5698babf7750ed6c744767887403de29accef802cb6f46"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_D,
                seed = ByteArray(0),
                iterations = 2,
                memoryKb = 64,
                parallelism = 2,
                length = 32,
            ),
        )
    }

    @Test
    fun argon2id_emptyPassword_t2_m64_p2() {
        val expectedHex = "fe47c31a9d8dc9c5fe0fcd896a299b1622070c4f8759240f50c51c18d8ebefe1"
        assertEquals(
            expectedHex,
            argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = ByteArray(0),
                iterations = 2,
                memoryKb = 64,
                parallelism = 2,
                length = 32,
            ),
        )
    }
    // endregion
}
