package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

class GetPinCodeImplTest {
    @Test
    fun `pin generator rejects common pin when search converges on final candidate`() {
        val cryptoGenerator = SequentialCryptoGenerator(9999, 2346)
        val useCase = GetPinCodeImpl(
            cryptoGenerator = cryptoGenerator,
        )

        val pin = useCase(
            config = PasswordGeneratorConfig.PinCode(length = 4),
        ).bindBlocking()

        assertEquals("2346", pin)
        assertEquals(2, cryptoGenerator.randomCalls)
    }
}

private class SequentialCryptoGenerator(
    vararg values: Int,
) : CryptoGenerator {
    private val values = values.toMutableList()

    var randomCalls: Int = 0
        private set

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = error("unused")

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = error("unused")

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = error("unused")

    override fun seed(length: Int): ByteArray = error("unused")

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = error("unused")

    override fun hashSha1(data: ByteArray): ByteArray = error("unused")

    override fun hashSha256(data: ByteArray): ByteArray = error("unused")

    override fun hashMd5(data: ByteArray): ByteArray = error("unused")

    override fun uuid(): String = error("unused")

    override fun random(): Int = error("unused")

    override fun random(range: IntRange): Int {
        val value = values.removeAt(0)
        require(value in range)
        randomCalls += 1
        return value
    }
}
