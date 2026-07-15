package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeRsaOaepHash
import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import org.kodein.di.DirectDI
import org.kodein.di.instance

class NativeCipherEncryptor(
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : CipherEncryptor {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun decode2(
        cipher: String,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): DecodeResult = runCatching {
        performDecode(cipher, symmetricCryptoKey, asymmetricCryptoKey)
    }.getOrElse { failure ->
        val type = cipher.substringBefore('.')
        throw DecodeException(
            "Failed to decode a cipher-text '$type.???'! Cause: ${failure.message}",
            failure,
        )
    }

    private fun performDecode(
        cipher: String,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): DecodeResult {
        val cipherParts = cipher.splitToSequence(".", limit = 3).toList()
        require(cipherParts.size == 2) {
            val redactedPrefix = cipherParts.dropLast(1).joinToString(separator = ".")
            "Cipher-text '$redactedPrefix.???' is not valid!"
        }
        val (cipherTypeRaw, cipherContent) = cipherParts
        val cipherType = CipherEncryptor.Type.entries
            .firstOrNull { it.type == cipherTypeRaw }
            ?: error("Cipher type $cipherTypeRaw is not supported!")
        val cipherArgs = cipherContent
            .split(CIPHER_DIVIDER)
            .map(base64Service::decode)

        fun requireSymmetricKey(): SymmetricCryptoKey2 = requireNotNull(symmetricCryptoKey) {
            "Symmetric Crypto Key must not be null, for decoding $cipherType."
        }

        fun requireAsymmetricKey(): AsymmetricCryptoKey = requireNotNull(asymmetricCryptoKey) {
            "Asymmetric Crypto Key must not be null, for decoding $cipherType."
        }

        val data = when (cipherType) {
            CipherEncryptor.Type.AesCbc256_B64 -> throwLegacyAesUnsupported()

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> {
                val key = requireSymmetricKey().requireAesCbc128_HmacSha256_B64()
                decodeAesCbcHmacSha256(cipherArgs, key.encKey, key.macKey)
            }

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> {
                val key = requireSymmetricKey().requireAesCbc256_HmacSha256_B64()
                decodeAesCbcHmacSha256(cipherArgs, key.encKey, key.macKey)
            }

            CipherEncryptor.Type.Rsa2048_OaepSha256_B64 -> decodeRsaOaep(
                args = cipherArgs,
                privateKey = requireAsymmetricKey().privateKey,
                hash = NativeRsaOaepHash.SHA_256,
                withMacArtifact = false,
            )

            CipherEncryptor.Type.Rsa2048_OaepSha1_B64 -> decodeRsaOaep(
                args = cipherArgs,
                privateKey = requireAsymmetricKey().privateKey,
                hash = NativeRsaOaepHash.SHA_1,
                withMacArtifact = false,
            )

            CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64 -> decodeRsaOaep(
                args = cipherArgs,
                privateKey = requireAsymmetricKey().privateKey,
                hash = NativeRsaOaepHash.SHA_256,
                withMacArtifact = true,
            )

            CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64 -> decodeRsaOaep(
                args = cipherArgs,
                privateKey = requireAsymmetricKey().privateKey,
                hash = NativeRsaOaepHash.SHA_1,
                withMacArtifact = true,
            )
        }
        return DecodeResult(data = data, type = cipherType)
    }

    private fun decodeAesCbcHmacSha256(
        args: List<ByteArray>,
        encKey: ByteArray,
        macKey: ByteArray,
    ): ByteArray {
        check(args.size == 3) {
            "The cipher must consist of exactly 3 parts: iv, ct, mac. The current cipher " +
                "contains ${args.size} parts which may cause unknown behaviour!"
        }
        val (iv, ciphertext, expectedMac) = args
        return NativeFileCrypto.decryptAesCbcHmacSha256(
            encryptionKey = encKey,
            macKey = macKey,
            iv = iv,
            ciphertext = ciphertext,
            expectedMac = expectedMac,
        )
    }

    private fun decodeRsaOaep(
        args: List<ByteArray>,
        privateKey: ByteArray,
        hash: NativeRsaOaepHash,
        withMacArtifact: Boolean,
    ): ByteArray {
        val expectedCount = if (withMacArtifact) 2 else 1
        check(args.size == expectedCount) {
            val expectedParts = if (withMacArtifact) "rsaCt, mac" else "rsaCt"
            "The cipher must consist of exactly $expectedCount part${if (expectedCount == 1) "" else "s"}: " +
                "$expectedParts. The current cipher contains ${args.size} parts which may cause unknown behaviour!"
        }
        // Types 5 and 6 historically require but do not authenticate the
        // second artifact. Keep that serialized behavior until a separate
        // protocol migration can account for existing vault material.
        return NativeCryptoPrimitives.rsaOaepDecrypt(
            privateKeyPkcs8 = privateKey,
            ciphertext = args[0],
            hash = hash,
        )
    }

    override fun encode2(
        cipherType: CipherEncryptor.Type,
        plainText: ByteArray,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): String {
        val artifacts = when (cipherType) {
            CipherEncryptor.Type.AesCbc256_B64 -> throwLegacyAesUnsupported()

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> {
                val key = requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, for encoding $cipherType."
                }.requireAesCbc128_HmacSha256_B64()
                encodeAesCbcHmacSha256(plainText, key.encKey, key.macKey)
            }

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> {
                val key = requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, for encoding $cipherType."
                }.requireAesCbc256_HmacSha256_B64()
                encodeAesCbcHmacSha256(plainText, key.encKey, key.macKey)
            }

            CipherEncryptor.Type.Rsa2048_OaepSha256_B64 -> {
                requireNotNull(asymmetricCryptoKey) {
                    "Asymmetric Crypto Key must not be null, for encoding $cipherType."
                }
                TODO("Encoding cipher type $cipherType is not supported yet.")
            }

            CipherEncryptor.Type.Rsa2048_OaepSha1_B64,
            CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64,
            CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64,
            -> TODO("Encoding cipher type $cipherType is not supported yet.")
        }
        return cipherType.type + "." + artifacts.joinToString(CIPHER_DIVIDER) { bytes ->
            base64Service.encodeToString(bytes)
        }
    }

    private fun encodeAesCbcHmacSha256(
        plainText: ByteArray,
        encKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray = cryptoGenerator.seed(16),
    ): List<ByteArray> {
        val result = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey = encKey,
            macKey = macKey,
            iv = iv,
            plaintext = plainText,
        )
        return listOf(iv, result.ciphertext, result.mac)
    }

    private companion object {
        const val CIPHER_DIVIDER = "|"
    }
}
