package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.usecase.GetPassphrase
import com.artemchep.keyguard.common.usecase.GetPinCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GetPasswordImplTest {
    @Test
    fun `password generator uses bounded random for required category characters`() {
        val cryptoGenerator = IntMinPasswordCryptoGenerator()
        val useCase = GetPasswordImpl(
            cryptoGenerator = cryptoGenerator,
            keyPairGenerator = UnusedKeyPairGenerator,
            gpgKeyGenerator = UnusedGpgKeyGenerator,
            getPassphrase = UnusedGetPassphrase,
            getPinCode = UnusedGetPinCode,
        )

        val result = useCase(
            context = GeneratorContext(host = null),
            config = PasswordGeneratorConfig.Password(
                length = 1,
                uppercaseChars = listOf('A', 'B', 'C'),
                lowercaseChars = emptyList(),
                numberChars = emptyList(),
                symbolChars = emptyList(),
                uppercaseMin = 1,
                lowercaseMin = 0,
                numbersMin = 0,
                symbolsMin = 0,
            ),
        ).bindBlocking()

        val value = assertIs<GetPasswordResult.Value>(result)
        assertEquals("A", value.value)
        assertEquals(0, cryptoGenerator.randomCalls)
        assertEquals(1, cryptoGenerator.randomRangeCalls.size)
        assertEquals(0..2, cryptoGenerator.randomRangeCalls.single())
    }

    @Test
    fun `gpg key generator returns async gpg key`() {
        val generated = GeneratedGpgKey(
            privateKeyArmored = "-----BEGIN PGP PRIVATE KEY BLOCK-----",
            publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
            fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567",
            metadata = GpgAgentKeyMetadata(
                keys = listOf(
                    GpgAgentKeyMetadataKey(
                        keygrip = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                        fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567",
                        capabilities = setOf("sign"),
                    ),
                ),
            ),
            userId = "Keyguard Test <gpg@test.invalid>",
            typeLabel = "Ed25519 + X25519",
        )
        val useCase = GetPasswordImpl(
            cryptoGenerator = IntMinPasswordCryptoGenerator(),
            keyPairGenerator = UnusedKeyPairGenerator,
            gpgKeyGenerator = FakeGpgKeyGenerator(generated),
            getPassphrase = UnusedGetPassphrase,
            getPinCode = UnusedGetPinCode,
        )

        val result = useCase(
            context = GeneratorContext(host = null),
            config = PasswordGeneratorConfig.GpgKey(
                config = GpgKeyConfig.Modern(
                    userId = generated.userId,
                ),
            ),
        ).bindBlocking()

        val value = assertIs<GetPasswordResult.AsyncGpgKey>(result)
        assertEquals(generated, value.gpgKey)
    }
}

private class IntMinPasswordCryptoGenerator : CryptoGenerator {
    var randomCalls: Int = 0
        private set

    val randomRangeCalls = mutableListOf<IntRange>()

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

    override fun random(): Int {
        randomCalls += 1
        return Int.MIN_VALUE
    }

    override fun random(range: IntRange): Int {
        randomRangeCalls += range
        return range.first
    }
}

private object UnusedKeyPairGenerator : KeyPairGenerator {
    override fun rsa(
        length: KeyPairGenerator.RsaLength,
    ): KeyParameterRawZero = error("unused")

    override fun ed25519(): KeyParameterRawZero = error("unused")

    override fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero = error("unused")

    override fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair = error("unused")

    override fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int? = error("unused")

    override fun getPrivateKeyLengthOrNull(
        privateKey: String,
    ): Int? = error("unused")
}

private object UnusedGpgKeyGenerator : GpgKeyGenerator {
    override fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey = error("unused")
}

private class FakeGpgKeyGenerator(
    private val generated: GeneratedGpgKey,
) : GpgKeyGenerator {
    override fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey = generated
}

private object UnusedGetPassphrase : GetPassphrase {
    override fun invoke(
        config: PasswordGeneratorConfig.Passphrase,
    ): IO<String> = error("unused")
}

private object UnusedGetPinCode : GetPinCode {
    override fun invoke(
        config: PasswordGeneratorConfig.PinCode,
    ): IO<String> = error("unused")
}
