package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.MasterKdfVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            version = MasterKdfVersion.V0,
        ).bindBlocking()

        assertEquals(1, cryptoGenerator.pbkdf2Calls.size)
        assertEquals(100_000, cryptoGenerator.pbkdf2Calls.single().iterations)
        assertEquals(0, cryptoGenerator.argon2Calls.size)
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
                version = MasterKdfVersion.fromRaw(999),
            ).bindBlocking()
        }
    }
}
