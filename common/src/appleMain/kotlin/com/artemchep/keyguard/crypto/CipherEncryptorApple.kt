package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyIsAlgorithmSupported
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA1
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA256
import platform.Security.kSecKeyOperationTypeDecrypt
import org.kodein.di.DirectDI
import org.kodein.di.instance

@OptIn(ExperimentalForeignApi::class)
class CipherEncryptorApple(
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : CipherEncryptor {
    companion object {
        private const val CIPHER_DIVIDER = "|"
    }

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
    ): DecodeResult = kotlin.runCatching {
        performDecode(
            cipher = cipher,
            symmetricCryptoKey = symmetricCryptoKey,
            asymmetricCryptoKey = asymmetricCryptoKey,
        )
    }.getOrElse { e ->
        val type = cipher.substringBefore('.')
        val cause = e.message
        throw DecodeException(
            "Failed to decode a cipher-text '$type.???'! Cause: $cause",
            e,
        )
    }

    private fun performDecode(
        cipher: String,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): DecodeResult {
        val cipherSeq = cipher
            .splitToSequence(".", limit = 3)
            .toList()
        require(cipherSeq.size == 2) {
            val c = cipherSeq
                .dropLast(1)
                .joinToString(separator = ".") { it }
            "Cipher-text '$c.???' is not valid!"
        }

        val (cipherTypeRaw, cipherContent) = cipherSeq
        val cipherType = CipherEncryptor.Type.entries
            .firstOrNull { it.type == cipherTypeRaw }
            ?: error("Cipher type $cipherTypeRaw is not supported!")
        val cipherArgs = cipherContent
            .split(CIPHER_DIVIDER)
            .map { base64 ->
                base64Service.decode(base64)
            }

        fun requireSymmetricCryptoKey() =
            requireNotNull(symmetricCryptoKey) {
                "Symmetric Crypto Key must not be null, for decoding $cipherType."
            }

        fun requireAsymmetricCryptoKey() =
            requireNotNull(asymmetricCryptoKey) {
                "Asymmetric Crypto Key must not be null, for decoding $cipherType."
            }

        val data = when (cipherType) {
            CipherEncryptor.Type.AesCbc256_B64 -> {
                val msg = "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
                        "Please upgrade your vault to migrate to a newer encryption type!"
                throw IllegalArgumentException(msg)
            }

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> {
                val cryptoKey = requireSymmetricCryptoKey().requireAesCbc128_HmacSha256_B64()
                decodeAesCbc_HmacSha256_B64(
                    args = cipherArgs,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> {
                val cryptoKey = requireSymmetricCryptoKey().requireAesCbc256_HmacSha256_B64()
                decodeAesCbc_HmacSha256_B64(
                    args = cipherArgs,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            CipherEncryptor.Type.Rsa2048_OaepSha256_B64 -> decodeRsaOaep(
                args = cipherArgs,
                privateKey = requireAsymmetricCryptoKey().privateKey,
                algorithm = checkNotNull(kSecKeyAlgorithmRSAEncryptionOAEPSHA256),
            )

            CipherEncryptor.Type.Rsa2048_OaepSha1_B64 -> decodeRsaOaep(
                args = cipherArgs,
                privateKey = requireAsymmetricCryptoKey().privateKey,
                algorithm = checkNotNull(kSecKeyAlgorithmRSAEncryptionOAEPSHA1),
            )

            CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64 -> decodeRsaOaepWithMac(
                args = cipherArgs,
                privateKey = requireAsymmetricCryptoKey().privateKey,
                algorithm = checkNotNull(kSecKeyAlgorithmRSAEncryptionOAEPSHA256),
            )

            CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64 -> decodeRsaOaepWithMac(
                args = cipherArgs,
                privateKey = requireAsymmetricCryptoKey().privateKey,
                algorithm = checkNotNull(kSecKeyAlgorithmRSAEncryptionOAEPSHA1),
            )
        }
        return DecodeResult(
            data = data,
            type = cipherType,
        )
    }

    private fun decodeAesCbc_HmacSha256_B64(
        args: List<ByteArray>,
        encKey: ByteArray,
        macKey: ByteArray,
    ): ByteArray {
        check(args.size == 3) {
            "The cipher must consist of exactly 3 parts: iv, ct, mac. The current cipher " +
                    "contains ${args.size} parts which may cause unknown behaviour!"
        }
        val (iv, ct, mac) = args
        val computedMac = cryptoGenerator.hmacSha256(
            key = macKey,
            data = iv + ct,
        )
        if (!computedMac.constantTimeEquals(mac)) {
            error("Message authentication codes do not match!")
        }
        return aesCbcPkcs7(
            data = ct,
            iv = iv,
            key = encKey,
            operation = kCCDecrypt,
        )
    }

    private fun decodeRsaOaep(
        args: List<ByteArray>,
        privateKey: ByteArray,
        algorithm: platform.Security.SecKeyAlgorithm,
    ): ByteArray {
        check(args.size == 1) {
            "The cipher must consist of exactly 1 part: rsaCt. The current cipher " +
                    "contains ${args.size} parts which may cause unknown behaviour!"
        }
        return rsaOaepDecrypt(
            privateKey = privateKey,
            cipherText = args[0],
            algorithm = algorithm,
        )
    }

    private fun decodeRsaOaepWithMac(
        args: List<ByteArray>,
        privateKey: ByteArray,
        algorithm: platform.Security.SecKeyAlgorithm,
    ): ByteArray {
        check(args.size == 2) {
            "The cipher must consist of exactly 2 parts: rsaCt, mac. The current cipher " +
                    "contains ${args.size} parts which may cause unknown behaviour!"
        }
        return rsaOaepDecrypt(
            privateKey = privateKey,
            cipherText = args[0],
            algorithm = algorithm,
        )
    }

    override fun encode2(
        cipherType: CipherEncryptor.Type,
        plainText: ByteArray,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): String {
        val artifacts = when (cipherType) {
            CipherEncryptor.Type.AesCbc256_B64 -> {
                val msg = "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
                        "Please upgrade your vault to migrate to a newer encryption type!"
                throw IllegalArgumentException(msg)
            }

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> {
                val cryptoKey = requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, for encoding $cipherType."
                }.requireAesCbc128_HmacSha256_B64()
                encodeAesCbc_HmacSha256_B64(
                    plainText = plainText,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> {
                val cryptoKey = requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, for encoding $cipherType."
                }.requireAesCbc256_HmacSha256_B64()
                encodeAesCbc_HmacSha256_B64(
                    plainText = plainText,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            CipherEncryptor.Type.Rsa2048_OaepSha256_B64,
            CipherEncryptor.Type.Rsa2048_OaepSha1_B64,
            CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64,
            CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64,
            -> TODO("Encoding cipher type $cipherType is not supported yet.")
        }
        val artifactsStr = artifacts
            .joinToString(separator = CIPHER_DIVIDER) { bytes ->
                base64Service.encodeToString(bytes)
            }
        return cipherType.type + "." + artifactsStr
    }

    private fun encodeAesCbc_HmacSha256_B64(
        plainText: ByteArray,
        iv: ByteArray = cryptoGenerator.seed(16),
        encKey: ByteArray,
        macKey: ByteArray,
    ): List<ByteArray> {
        val ct = aesCbcPkcs7(
            data = plainText,
            iv = iv,
            key = encKey,
            operation = kCCEncrypt,
        )
        val mac = cryptoGenerator.hmacSha256(
            key = macKey,
            data = iv + ct,
        )
        return listOf(iv, ct, mac)
    }

    private fun rsaOaepDecrypt(
        privateKey: ByteArray,
        cipherText: ByteArray,
        algorithm: platform.Security.SecKeyAlgorithm,
    ): ByteArray = memScoped {
        val pkcs1PrivateKey = unwrapPkcs8PrivateKey(privateKey)
        val key = createPrivateSecKey(pkcs1PrivateKey)
        try {
            check(SecKeyIsAlgorithmSupported(key, kSecKeyOperationTypeDecrypt, algorithm)) {
                "RSA OAEP algorithm is not supported by Security.framework."
            }

            val error = alloc<CFErrorRefVar>()
            val decrypted = cipherText.useCFData { cipherData ->
                SecKeyCreateDecryptedData(
                    key = key,
                    algorithm = algorithm,
                    ciphertext = cipherData,
                    error = error.ptr,
                )
            } ?: error("RSA OAEP decryption failed.")
            try {
                decrypted.toByteArray()
            } finally {
                CFRelease(decrypted)
            }
        } finally {
            CFRelease(key)
        }
    }

    private fun createPrivateSecKey(
        pkcs1PrivateKey: ByteArray,
    ): SecKeyRef = memScoped {
        val attrs = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = null,
            valueCallBacks = null,
        )
        checkNotNull(attrs) {
            "Could not allocate Security.framework key attributes."
        }
        try {
            CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeRSA)
            CFDictionarySetValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPrivate)

            val error = alloc<CFErrorRefVar>()
            pkcs1PrivateKey.useCFData { keyData ->
                SecKeyCreateWithData(
                    keyData = keyData,
                    attributes = attrs,
                    error = error.ptr,
                )
            } ?: error("Could not import RSA private key.")
        } finally {
            CFRelease(attrs)
        }
    }
}

private fun unwrapPkcs8PrivateKey(
    privateKey: ByteArray,
): ByteArray {
    val reader = DerReader(privateKey)
    val outer = reader.readConstructed(0x30)
    outer.readIntegerBytes()
    return when (outer.peekTag()) {
        0x02 -> privateKey
        0x30 -> {
            outer.readAny()
            outer.readOctetString()
        }
        else -> privateKey
    }
}
