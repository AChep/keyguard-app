package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.MasterKdfOutOfMemoryException
import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GenerateMasterHashUtilsTest {
    @Test
    fun `v0 hash uses PBKDF2 100000 iterations`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )

        utils.hash(
            password = byteArrayOf(1, 2, 3),
            salt = byteArrayOf(4, 5, 6),
            kdf = MasterKdf.Pbkdf2Legacy,
        ).bindBlocking()

        assertEquals(1, cryptoGenerator.pbkdf2Calls.size)
        assertEquals(100_000, cryptoGenerator.pbkdf2Calls.single().iterations)
        assertEquals(0, cryptoGenerator.argon2Calls.size)
    }

    @Test
    fun `v1 hash uses the frozen Argon2id parameters`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )

        utils.hash(
            password = byteArrayOf(1, 2, 3),
            salt = byteArrayOf(4, 5, 6),
            kdf = MasterKdf.Argon2id.V1,
        ).bindBlocking()

        assertEquals(0, cryptoGenerator.pbkdf2Calls.size)
        assertEquals(
            Argon2Call(
                mode = Argon2Mode.ARGON2_ID,
                iterations = 3,
                memoryKb = 65536,
                parallelism = 4,
            ),
            cryptoGenerator.argon2Calls.single(),
        )
    }

    @Test
    fun `custom hash uses its Argon2id parameters`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )

        utils.hash(
            password = byteArrayOf(1, 2, 3),
            salt = byteArrayOf(4, 5, 6),
            kdf = MasterKdf.Argon2id(
                iterations = 5,
                memoryKib = 131072,
                parallelism = 2,
            ),
        ).bindBlocking()

        assertEquals(
            Argon2Call(
                mode = Argon2Mode.ARGON2_ID,
                iterations = 5,
                memoryKb = 131072,
                parallelism = 2,
            ),
            cryptoGenerator.argon2Calls.single(),
        )
    }

    @Test
    fun `native resource limit fails with out of memory exception`() {
        val cryptoGenerator = object : RecordingCryptoGenerator() {
            override fun argon2(
                mode: Argon2Mode,
                seed: ByteArray,
                salt: ByteArray,
                iterations: Int,
                memoryKb: Int,
                parallelism: Int,
            ): ByteArray = throw NativeCryptoException(
                operation = "argon2",
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )

        assertFailsWith<MasterKdfOutOfMemoryException> {
            utils.hash(
                password = byteArrayOf(1, 2, 3),
                salt = byteArrayOf(4, 5, 6),
                kdf = MasterKdf.Argon2id.V1,
            ).bindBlocking()
        }
    }

    @Test
    fun `other native failures are not out of memory`() {
        val cryptoGenerator = object : RecordingCryptoGenerator() {
            override fun argon2(
                mode: Argon2Mode,
                seed: ByteArray,
                salt: ByteArray,
                iterations: Int,
                memoryKb: Int,
                parallelism: Int,
            ): ByteArray = throw NativeCryptoException(
                operation = "argon2",
                code = NativeCryptoErrorCode.INVALID_ARGUMENT,
            )
        }
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )

        val e = assertFailsWith<NativeCryptoException> {
            utils.hash(
                password = byteArrayOf(1, 2, 3),
                salt = byteArrayOf(4, 5, 6),
                kdf = MasterKdf.Argon2id.V1,
            ).bindBlocking()
        }
        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, e.code)
    }

    @Test
    fun `custom Argon2id derivation is deterministic and differs from v1`() {
        val cryptoGenerator = NativeCryptoGenerator()
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )
        val password = "password".encodeToByteArray()
        val salt = ByteArray(64) { it.toByte() }
        val custom = MasterKdf.Argon2id(
            iterations = 2,
            memoryKib = 16384,
            parallelism = 1,
        )

        val v1 = utils.hash(password, salt, MasterKdf.Argon2id.V1).bindBlocking()
        val first = utils.hash(password, salt, custom).bindBlocking()
        val second = utils.hash(password, salt, custom).bindBlocking()

        // Version 1 must keep deriving with its literal parameters.
        val expectedV1 = cryptoGenerator.argon2(
            mode = Argon2Mode.ARGON2_ID,
            seed = password,
            salt = salt,
            iterations = 3,
            memoryKb = 65536,
            parallelism = 4,
        )
        assertContentEquals(expectedV1, v1)
        assertContentEquals(first, second)
        assertFalse(first.contentEquals(v1))
    }

    @Test
    fun `unsupported hash version fails with typed exception`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val utils = GenerateMasterHashUtils(
            cryptoGenerator = cryptoGenerator,
        )

        assertFailsWith<UnsupportedMasterKdfVersionException> {
            utils.hash(
                password = byteArrayOf(1, 2, 3),
                salt = byteArrayOf(4, 5, 6),
                kdf = MasterKdf.Unsupported(MasterKdfVersion.fromRaw(999)),
            ).bindBlocking()
        }
    }
}
