package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeRsaOaepHash
import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.usecase.util.pbk
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Permanent differential coverage for native compatibility.
 *
 * Bouncy Castle deliberately remains on the test classpath as an independent
 * compatibility oracle; none of these helpers are reachable from production.
 */
class NativeCryptoBouncyCastleDifferentialTest {
    private val base64 = Base64ServiceJvm()
    private val cipherEncryptor = CipherEncryptorImpl(
        cryptoGenerator = CryptoGeneratorJvm(),
        base64Service = base64,
    )
    private val fileEncryptor = FileEncryptorJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )

    @Test
    fun `native Bitwarden AES HMAC frames decrypt and authenticate with BC`() {
        symmetricCases.forEach { case ->
            val encoded = cipherEncryptor.encode2(
                cipherType = case.type,
                plainText = plaintext,
                symmetricCryptoKey = SymmetricCryptoKey2(case.key),
                asymmetricCryptoKey = null,
            )

            val (type, content) = encoded.split('.', limit = 2)
            val artifacts = content.split('|').map(base64::decode)
            assertEquals(case.type.type, type)
            assertEquals(3, artifacts.size)

            val (iv, ciphertext, mac) = artifacts
            assertEquals(16, iv.size)
            assertContentEquals(
                expected = bouncyCastleHmacSha256(case.macKey, iv + ciphertext),
                actual = mac,
            )
            assertContentEquals(
                expected = plaintext,
                actual = bouncyCastleAesCbcPkcs7(
                    key = case.encKey,
                    iv = iv,
                    data = ciphertext,
                    encrypt = false,
                ),
            )
        }
    }

    @Test
    fun `BC Bitwarden AES HMAC frames decrypt with native crypto`() {
        symmetricCases.forEachIndexed { index, case ->
            val iv = ByteArray(16) { (it + index * 17).toByte() }
            val ciphertext = bouncyCastleAesCbcPkcs7(
                key = case.encKey,
                iv = iv,
                data = plaintext,
                encrypt = true,
            )
            val mac = bouncyCastleHmacSha256(case.macKey, iv + ciphertext)
            val encoded = encodeCipher(case.type, iv, ciphertext, mac)

            val decoded = cipherEncryptor.decode2(
                cipher = encoded,
                symmetricCryptoKey = SymmetricCryptoKey2(case.key),
                asymmetricCryptoKey = null,
            )

            assertEquals(case.type, decoded.type)
            assertContentEquals(plaintext, decoded.data)
        }
    }

    @Test
    fun `tampered MAC is rejected before Bitwarden plaintext is returned`() {
        val case = symmetricCases.last()
        val iv = ByteArray(16) { (it + 73).toByte() }
        val ciphertext = bouncyCastleAesCbcPkcs7(
            key = case.encKey,
            iv = iv,
            data = plaintext,
            encrypt = true,
        )
        val tamperedMac = bouncyCastleHmacSha256(case.macKey, iv + ciphertext).also { mac ->
            mac[0] = (mac[0].toInt() xor 1).toByte()
        }

        val failure = assertFailsWith<DecodeException> {
            cipherEncryptor.decode2(
                cipher = encodeCipher(case.type, iv, ciphertext, tamperedMac),
                symmetricCryptoKey = SymmetricCryptoKey2(case.key),
                asymmetricCryptoKey = null,
            )
        }

        assertEquals("Message authentication codes do not match!", failure.cause?.message)
    }

    @Test
    fun `fused native file frame exactly matches BC bytes`() {
        val case = symmetricCases.last()
        val iv = ByteArray(FileEncryptionFormat.IV_LENGTH) { (it + 101).toByte() }
        val ciphertext = bouncyCastleAesCbcPkcs7(
            key = case.encKey,
            iv = iv,
            data = plaintext,
            encrypt = true,
        )
        val mac = bouncyCastleHmacSha256(case.macKey, iv + ciphertext)
        val expected = byteArrayOf(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte) +
            iv + mac + ciphertext

        val encoded = NativeFileCrypto.encode(
            data = plaintext,
            key = case.key,
            iv = iv,
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun `BC file frames for types 1 and 2 decrypt with native crypto`() {
        symmetricCases.forEachIndexed { index, case ->
            val iv = ByteArray(FileEncryptionFormat.IV_LENGTH) { (it + index * 29).toByte() }
            val ciphertext = bouncyCastleAesCbcPkcs7(
                key = case.encKey,
                iv = iv,
                data = plaintext,
                encrypt = true,
            )
            val mac = bouncyCastleHmacSha256(case.macKey, iv + ciphertext)
            val frame = byteArrayOf(case.type.byte) + iv + mac + ciphertext

            assertContentEquals(plaintext, fileEncryptor.decode(frame, case.key))
        }
    }

    @Test
    fun `BC OAEP ciphertexts decrypt through every native Bitwarden RSA type`() {
        rsaCases.forEach { case ->
            val ciphertext = bouncyCastleRsaOaep(
                encrypt = true,
                key = rsaFixture.keyPair.public as RSAKeyParameters,
                data = plaintext,
                hash = case.hash,
            )
            val artifacts = if (case.hasIgnoredMacArtifact) {
                arrayOf(ciphertext, byteArrayOf(0x01, 0x02, 0x03))
            } else {
                arrayOf(ciphertext)
            }

            val decoded = cipherEncryptor.decode2(
                cipher = encodeCipher(case.type, *artifacts),
                symmetricCryptoKey = null,
                asymmetricCryptoKey = AsymmetricCryptoKey(rsaFixture.privateKeyPkcs8),
            )

            assertEquals(case.type, decoded.type)
            assertContentEquals(plaintext, decoded.data)
        }
    }

    @Test
    fun `native OAEP ciphertexts decrypt with BC for SHA1 and SHA256`() {
        listOf(NativeRsaOaepHash.SHA_1, NativeRsaOaepHash.SHA_256).forEach { hash ->
            val ciphertext = NativeCryptoPrimitives.rsaOaepEncrypt(
                publicKeySpki = rsaFixture.publicKeySpki,
                plaintext = plaintext,
                hash = hash,
            )
            val decoded = bouncyCastleRsaOaep(
                encrypt = false,
                key = rsaFixture.keyPair.private as RSAPrivateCrtKeyParameters,
                data = ciphertext,
                hash = hash,
            )

            assertContentEquals(plaintext, decoded)
        }
    }

    @Test
    fun `native RSA types 5 and 6 retain BC ignored MAC artifact behavior`() {
        rsaCases.filter { it.hasIgnoredMacArtifact }.forEach { case ->
            val ciphertext = bouncyCastleRsaOaep(
                encrypt = true,
                key = rsaFixture.keyPair.public as RSAKeyParameters,
                data = plaintext,
                hash = case.hash,
            )

            val first = decodeRsaWithArtifact(case.type, ciphertext, byteArrayOf(0x00))
            val second = decodeRsaWithArtifact(case.type, ciphertext, ByteArray(32) { 0x7f })

            assertContentEquals(plaintext, first)
            assertContentEquals(first, second)
        }
    }

    @Test
    fun `native PKCS8 to SPKI bytes exactly match BC`() {
        assertContentEquals(
            expected = rsaFixture.publicKeySpki,
            actual = pbk(rsaFixture.privateKeyPkcs8),
        )
    }

    private fun decodeRsaWithArtifact(
        type: CipherEncryptor.Type,
        ciphertext: ByteArray,
        artifact: ByteArray,
    ): ByteArray = cipherEncryptor.decode2(
        cipher = encodeCipher(type, ciphertext, artifact),
        symmetricCryptoKey = null,
        asymmetricCryptoKey = AsymmetricCryptoKey(rsaFixture.privateKeyPkcs8),
    ).data

    private fun encodeCipher(
        type: CipherEncryptor.Type,
        vararg artifacts: ByteArray,
    ): String = type.type + "." + artifacts.joinToString("|") { base64.encodeToString(it) }

    private companion object {
        val plaintext = "Native crypto differential interoperability".encodeToByteArray()

        val symmetricCases = listOf(
            SymmetricCase(
                type = CipherEncryptor.Type.AesCbc128_HmacSha256_B64,
                key = ByteArray(32) { (it + 1).toByte() },
                encKeyLength = 16,
            ),
            SymmetricCase(
                type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
                key = ByteArray(64) { (it + 33).toByte() },
                encKeyLength = 32,
            ),
        )

        val rsaCases = listOf(
            RsaCase(
                type = CipherEncryptor.Type.Rsa2048_OaepSha256_B64,
                hash = NativeRsaOaepHash.SHA_256,
                hasIgnoredMacArtifact = false,
            ),
            RsaCase(
                type = CipherEncryptor.Type.Rsa2048_OaepSha1_B64,
                hash = NativeRsaOaepHash.SHA_1,
                hasIgnoredMacArtifact = false,
            ),
            RsaCase(
                type = CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64,
                hash = NativeRsaOaepHash.SHA_256,
                hasIgnoredMacArtifact = true,
            ),
            RsaCase(
                type = CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64,
                hash = NativeRsaOaepHash.SHA_1,
                hasIgnoredMacArtifact = true,
            ),
        )

        val rsaFixture: RsaFixture by lazy {
            val generator = RSAKeyPairGenerator().apply {
                init(
                    RSAKeyGenerationParameters(
                        BigInteger.valueOf(65537),
                        SecureRandom(),
                        2048,
                        80,
                    ),
                )
            }
            val keyPair = generator.generateKeyPair()
            RsaFixture(
                keyPair = keyPair,
                privateKeyPkcs8 = PrivateKeyInfoFactory
                    .createPrivateKeyInfo(keyPair.private)
                    .encoded,
                publicKeySpki = SubjectPublicKeyInfoFactory
                    .createSubjectPublicKeyInfo(keyPair.public)
                    .encoded,
            )
        }
    }
}

private data class SymmetricCase(
    val type: CipherEncryptor.Type,
    val key: ByteArray,
    val encKeyLength: Int,
) {
    val encKey: ByteArray = key.copyOfRange(0, encKeyLength)
    val macKey: ByteArray = key.copyOfRange(encKeyLength, key.size)
}

private data class RsaCase(
    val type: CipherEncryptor.Type,
    val hash: NativeRsaOaepHash,
    val hasIgnoredMacArtifact: Boolean,
)

private data class RsaFixture(
    val keyPair: AsymmetricCipherKeyPair,
    val privateKeyPkcs8: ByteArray,
    val publicKeySpki: ByteArray,
)

private fun bouncyCastleHmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val hmac = HMac(SHA256Digest()).apply {
        init(KeyParameter(key))
    }
    hmac.update(data, 0, data.size)
    return ByteArray(hmac.macSize).also { output ->
        hmac.doFinal(output, 0)
    }
}

private fun bouncyCastleRsaOaep(
    encrypt: Boolean,
    key: RSAKeyParameters,
    data: ByteArray,
    hash: NativeRsaOaepHash,
): ByteArray {
    fun digest(): Digest = when (hash) {
        NativeRsaOaepHash.SHA_1 -> SHA1Digest()
        NativeRsaOaepHash.SHA_256 -> SHA256Digest()
    }

    return OAEPEncoding(
        RSAEngine(),
        digest(),
        digest(),
        null,
    ).run {
        init(encrypt, key)
        processBlock(data, 0, data.size)
    }
}
