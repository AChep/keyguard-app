package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenerateMasterKeyUseCaseImplTest {
    @Test
    fun `v0 master key uses PBKDF2 10000 iterations`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val useCase = GenerateMasterKeyUseCaseImpl(
            logRepository = NoOpLogRepository,
            cryptoGenerator = cryptoGenerator,
        )

        useCase(
            password = MasterPassword.of("password"),
            salt = MasterPasswordHash(
                version = MasterKdfVersion.V0,
                byteArray = byteArrayOf(1, 2, 3, 4),
            ),
        ).bindBlocking()

        assertEquals(1, cryptoGenerator.pbkdf2Calls.size)
        assertEquals(10_000, cryptoGenerator.pbkdf2Calls.single().iterations)
        assertEquals(0, cryptoGenerator.argon2Calls.size)
    }

    @Test
    fun `master key output keeps v0 version`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val useCase = GenerateMasterKeyUseCaseImpl(
            logRepository = NoOpLogRepository,
            cryptoGenerator = cryptoGenerator,
        )

        val key = useCase(
            password = MasterPassword.of("password"),
            salt = MasterPasswordHash(
                version = MasterKdfVersion.V0,
                byteArray = byteArrayOf(9, 8, 7),
            ),
        ).bindBlocking()

        assertEquals(MasterKdfVersion.V0, key.version)
    }

    @Test
    fun `v1 master key uses Argon2 path`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val useCase = GenerateMasterKeyUseCaseImpl(
            logRepository = NoOpLogRepository,
            cryptoGenerator = cryptoGenerator,
        )

        val key = useCase(
            password = MasterPassword.of("password"),
            salt = MasterPasswordHash(
                version = MasterKdfVersion.V1,
                byteArray = byteArrayOf(5, 6, 7, 8),
            ),
        ).bindBlocking()

        assertEquals(MasterKdfVersion.V1, key.version)
        assertEquals(0, cryptoGenerator.pbkdf2Calls.size)
        assertEquals(1, cryptoGenerator.argon2Calls.size)
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
    fun `unsupported key version fails with typed exception`() {
        val cryptoGenerator = RecordingCryptoGenerator()
        val useCase = GenerateMasterKeyUseCaseImpl(
            logRepository = NoOpLogRepository,
            cryptoGenerator = cryptoGenerator,
        )

        assertFailsWith<UnsupportedMasterKdfVersionException> {
            useCase(
                password = MasterPassword.of("password"),
                salt = MasterPasswordHash(
                    version = MasterKdfVersion.fromRaw(999),
                    byteArray = byteArrayOf(5, 6, 7, 8),
                ),
            ).bindBlocking()
        }
    }
}

internal object NoOpLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}

internal data class Pbkdf2Call(
    val iterations: Int,
    val length: Int,
)

internal data class Argon2Call(
    val mode: Argon2Mode,
    val iterations: Int,
    val memoryKb: Int,
    val parallelism: Int,
)

internal class RecordingCryptoGenerator : CryptoGenerator {
    val pbkdf2Calls = mutableListOf<Pbkdf2Call>()
    val argon2Calls = mutableListOf<Argon2Call>()

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray {
        pbkdf2Calls += Pbkdf2Call(
            iterations = iterations,
            length = length,
        )
        return byteArrayOf(0x11)
    }

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray {
        argon2Calls += Argon2Call(
            mode = mode,
            iterations = iterations,
            memoryKb = memoryKb,
            parallelism = parallelism,
        )
        return byteArrayOf(0x22)
    }

    override fun seed(length: Int): ByteArray = byteArrayOf()

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = byteArrayOf()

    override fun hashSha1(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashSha256(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashMd5(data: ByteArray): ByteArray = byteArrayOf()

    override fun uuid(): String = "test-uuid"

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}
