package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MasterKeyWrappingTest {
    private val cryptoGenerator = NativeCryptoGenerator()

    private val cipherEncryptor = NativeCipherEncryptor(
        cryptoGenerator = cryptoGenerator,
        base64Service = Base64ServiceImpl(),
    )

    private val log = LogRepositoryBridge(emptyList())

    private val wrapping = MasterKeyWrapping(
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
    )

    private val confirm = AuthConfirmMasterKeyUseCaseImpl(
        generateMasterHashUseCase = GenerateMasterHashUseCaseImpl(log, cryptoGenerator),
        generateMasterKeyUseCase = GenerateMasterKeyUseCaseImpl(log, cryptoGenerator),
        masterKeyWrapping = wrapping,
    )

    private val rewrap = RewrapMasterKeyUseCaseImpl(
        masterKeyWrapping = wrapping,
        generateMasterSaltUseCase = GenerateMasterSaltUseCaseImpl(cryptoGenerator),
    )

    private val generate = AuthGenerateMasterKeyUseCaseImpl(
        cryptoGenerator = cryptoGenerator,
        rewrapMasterKeyUseCase = rewrap,
    )

    @Test
    fun `the wrapped format of version 2 is frozen`() {
        val result = confirm(token(GOLDEN))(MasterPassword.of(PASSWORD)).bindBlocking()

        assertEquals(MasterKdfVersion.V2, result.key.version)
        assertContentEquals(MASTER_KEY, result.key.byteArray)
        assertEquals(KDF, result.token.kdf)
    }

    @Test
    fun `a wrong password does not unwrap the master key`() {
        val result = confirm(token(GOLDEN))(MasterPassword.of("$PASSWORD!")).failure()

        assertIs<PasswordMismatchException>(result)
    }

    @Test
    fun `other parameters do not unwrap the master key`() {
        val other = KDF.copy(iterations = KDF.iterations + 1)
        val result = confirm(token(GOLDEN, other))(MasterPassword.of(PASSWORD)).failure()

        assertIs<PasswordMismatchException>(result)
    }

    @Test
    fun `an unsupported key derivation does not unwrap the master key`() {
        val kdf = MasterKdf.Unsupported(MasterKdfVersion.V2)
        val result = confirm(token(GOLDEN, kdf))(MasterPassword.of(PASSWORD)).failure()

        assertIs<PasswordMismatchException>(result)
    }

    @Test
    fun `a wrap without a MAC is rejected`() {
        val parts = GOLDEN.substringAfter('.').split('|')
        val noMac = "0." + parts.take(2).joinToString("|")
        val result = confirm(token(noMac))(MasterPassword.of(PASSWORD)).failure()

        assertIs<PasswordMismatchException>(result)
    }

    @Test
    fun `a new master key is random and unwraps with the password`() {
        val first = generate(KDF)(MasterPassword.of(PASSWORD)).bindBlocking()
        val second = generate(KDF)(MasterPassword.of(PASSWORD)).bindBlocking()

        assertEquals(MasterKdfVersion.V2, first.key.version)
        assertEquals(MasterKeyWrapping.MASTER_KEY_SIZE, first.key.byteArray.size)
        assertFalse(first.key.byteArray.contentEquals(second.key.byteArray))
        val token = assertIs<FingerprintPassword.Wrapped>(first.token)
        assertEquals(64, token.salt.byteArray.size)

        val unwrapped = confirm(token)(MasterPassword.of(PASSWORD)).bindBlocking()
        assertEquals(first.key, unwrapped.key)
    }

    @Test
    fun `a re-wrap keeps the master key under a new salt and password`() {
        val created = generate(KDF)(MasterPassword.of(PASSWORD)).bindBlocking()
        val other = KDF.copy(parallelism = 2)

        val token = rewrap(created.key, MasterPassword.of("new"), other).bindBlocking()

        assertFalse(token.salt == created.token.salt)
        val unwrapped = confirm(token)(MasterPassword.of("new")).bindBlocking()
        assertEquals(created.key, unwrapped.key)
        assertIs<PasswordMismatchException>(confirm(token)(MasterPassword.of(PASSWORD)).failure())
    }

    @Test
    fun `the derived format keeps working`() {
        val kdf = MasterKdf.Argon2id.V1
        val salt = MasterPasswordSalt(SALT)
        val hash = GenerateMasterHashUseCaseImpl(log, cryptoGenerator)(MasterPassword.of(PASSWORD), salt, kdf)
            .bindBlocking()
        val key = GenerateMasterKeyUseCaseImpl(log, cryptoGenerator)(MasterPassword.of(PASSWORD), hash)
            .bindBlocking()
        val token = FingerprintPassword.Derived(
            kdf = kdf,
            hash = hash,
            salt = salt,
        )

        val result = confirm(token)(MasterPassword.of(PASSWORD)).bindBlocking()
        assertEquals(MasterKdfVersion.V1, result.key.version)
        assertEquals(key, result.key)
        assertIs<PasswordMismatchException>(confirm(token)(MasterPassword.of("wrong")).failure())
    }

    private fun token(
        wrapped: String,
        kdf: MasterKdf = KDF,
    ) = FingerprintPassword.Wrapped(
        kdf = kdf,
        salt = MasterPasswordSalt(SALT),
        wrappedKey = wrapped.encodeToByteArray(),
    )

    private fun IO<AuthResult>.failure(): Throwable =
        when (val result = attempt().bindBlocking()) {
            is Either.Left -> result.value
            is Either.Right -> error("Expected a failure")
        }

    private companion object {
        const val PASSWORD = "correct horse battery staple"

        val KDF = MasterKdf.Argon2id(
            iterations = 2,
            memoryKib = 16 * 1024,
            parallelism = 1,
        )

        val SALT = ByteArray(64) { it.toByte() }

        val MASTER_KEY = ByteArray(32) { (0xA0 + it).toByte() }

        /**
         * The [MASTER_KEY] wrapped with the key that [PASSWORD] derives
         * with [KDF] under [SALT].
         */
        const val GOLDEN = "2.9EU+Uy1xKXCv9nSaiDJ7aA==|" +
                "npWzizAGvMH1CtNFjIvv0pvgieXR3EPF6zfPEvSIqgf76m2TQ5gh+bPN5BWwZMvr|" +
                "Yd9Ix5mwSwvlw3kgmUq489p2SDwrO/54FC2KPv9TcXw="
    }
}
