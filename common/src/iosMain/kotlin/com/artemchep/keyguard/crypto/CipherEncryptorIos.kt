package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
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
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
class CipherEncryptorIos(
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

    private fun aesCbcPkcs7(
        data: ByteArray,
        iv: ByteArray,
        key: ByteArray,
        operation: UInt,
    ): ByteArray = memScoped {
        val blockSize = kCCBlockSizeAES128.toInt()
        require(iv.size == blockSize) {
            "AES-CBC requires a ${kCCBlockSizeAES128}-byte initialization vector."
        }
        require(key.size == 16 || key.size == 32) {
            "AES-CBC requires a 16-byte or 32-byte key."
        }

        val output = ByteArray(data.size + blockSize)
        val outputMoved = alloc<ULongVar>()
        val status = key.usePinned { keyPinned ->
            iv.usePinned { ivPinned ->
                data.usePinnedOrNull { dataPtr ->
                    output.usePinned { outputPinned ->
                        CCCrypt(
                            op = operation,
                            alg = kCCAlgorithmAES,
                            options = kCCOptionPKCS7Padding,
                            key = keyPinned.addressOf(0),
                            keyLength = key.size.convert(),
                            iv = ivPinned.addressOf(0),
                            dataIn = dataPtr,
                            dataInLength = data.size.convert(),
                            dataOut = outputPinned.addressOf(0),
                            dataOutAvailable = output.size.convert(),
                            dataOutMoved = outputMoved.ptr,
                        )
                    }
                }
            }
        }
        check(status == kCCSuccess) {
            "AES-CBC operation failed with status $status."
        }
        output.copyOf(outputMoved.value.toInt())
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

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.usePinnedOrNull(
    block: (CPointer<ByteVar>?) -> T,
): T {
    if (isEmpty()) {
        return block(null)
    }
    return usePinned { pinned ->
        block(pinned.addressOf(0))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val size = CFDataGetLength(this).toInt()
    if (size == 0) {
        return ByteArray(0)
    }
    val bytes = checkNotNull(CFDataGetBytePtr(this)) {
        "CFData bytes pointer is null."
    }
    return ByteArray(size).also { output ->
        output.usePinned { outputPinned ->
            memcpy(
                outputPinned.addressOf(0),
                bytes,
                size.convert(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.useCFData(
    block: (CFDataRef) -> T,
): T {
    val data = usePinnedOrNull { ptr ->
        CFDataCreate(
            allocator = null,
            bytes = ptr?.reinterpret(),
            length = size.convert(),
        )
    }
    checkNotNull(data) {
        "Could not allocate CFData."
    }
    return try {
        block(data)
    } finally {
        CFRelease(data)
    }
}

private fun ByteArray.constantTimeEquals(
    other: ByteArray,
): Boolean {
    if (size != other.size) {
        return false
    }
    var diff = 0
    for (i in indices) {
        diff = diff or (this[i].toInt() xor other[i].toInt())
    }
    return diff == 0
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

private class DerReader(
    private val data: ByteArray,
) {
    private var offset = 0

    fun peekTag(): Int? = data.getOrNull(offset)?.toInt()?.and(0xff)

    fun readConstructed(
        expectedTag: Int,
    ): DerReader {
        val value = readValue(expectedTag)
        return DerReader(value)
    }

    fun readIntegerBytes(): ByteArray = readValue(0x02)

    fun readOctetString(): ByteArray = readValue(0x04)

    fun readAny(): ByteArray {
        readByte()
        val length = readLength()
        return readBytes(length)
    }

    private fun readValue(
        expectedTag: Int,
    ): ByteArray {
        val tag = readByte()
        require(tag == expectedTag) {
            "Unexpected DER tag $tag, expected $expectedTag."
        }
        val length = readLength()
        return readBytes(length)
    }

    private fun readLength(): Int {
        val first = readByte()
        if (first and 0x80 == 0) {
            return first
        }
        val count = first and 0x7f
        require(count in 1..4) {
            "Unsupported DER length."
        }
        var length = 0
        repeat(count) {
            length = (length shl 8) or readByte()
        }
        return length
    }

    private fun readByte(): Int {
        require(offset < data.size) {
            "Unexpected end of DER data."
        }
        return data[offset++].toInt() and 0xff
    }

    private fun readBytes(
        length: Int,
    ): ByteArray {
        require(length >= 0 && offset + length <= data.size) {
            "Invalid DER length."
        }
        return data.copyOfRange(offset, offset + length)
            .also {
                offset += length
            }
    }
}
