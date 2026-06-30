package com.artemchep.keyguard.util.foundation.crypto

import diglol.crypto.Argon2
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.CoreCrypto.kCCHmacAlgMD5
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA512
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
actual class PlatformCryptoPrimitives actual constructor() : CryptoPrimitives {
    actual override fun hkdfSha256(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray {
        require(length >= 0) {
            "HKDF output length must not be negative."
        }
        require(length <= MAX_HKDF_SHA256_LENGTH) {
            "HKDF output length must not exceed $MAX_HKDF_SHA256_LENGTH bytes."
        }

        val prk = if (salt != null) {
            hmacSha256(
                key = salt,
                data = seed,
            )
        } else {
            seed
        }
        val infoBytes = info ?: ByteArray(0)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = ByteArray(previous.size + infoBytes.size + 1)
            previous.copyInto(input)
            infoBytes.copyInto(input, destinationOffset = previous.size)
            input[input.lastIndex] = counter.toByte()

            previous = hmacSha256(
                key = prk,
                data = input,
            )

            val take = minOf(previous.size, length - offset)
            previous.copyInto(output, destinationOffset = offset, endIndex = take)
            offset += take
            counter += 1
        }
        return output
    }

    actual override fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray {
        require(iterations > 0) {
            "PBKDF2 iterations must be positive."
        }
        require(length >= 0) {
            "PBKDF2 output length must not be negative."
        }
        if (length == 0) {
            return ByteArray(0)
        }

        val output = ByteArray(length)
        val blockCount = (length + HMAC_SHA256_SIZE - 1) / HMAC_SHA256_SIZE
        var outputOffset = 0
        for (blockIndex in 1..blockCount) {
            val blockSeed = ByteArray(salt.size + INT_SIZE_BYTES)
            salt.copyInto(blockSeed)
            blockSeed[blockSeed.lastIndex - 3] = (blockIndex ushr 24).toByte()
            blockSeed[blockSeed.lastIndex - 2] = (blockIndex ushr 16).toByte()
            blockSeed[blockSeed.lastIndex - 1] = (blockIndex ushr 8).toByte()
            blockSeed[blockSeed.lastIndex] = blockIndex.toByte()

            var u = hmacSha256(
                key = seed,
                data = blockSeed,
            )
            val block = u.copyOf()
            repeat(iterations - 1) {
                u = hmacSha256(
                    key = seed,
                    data = u,
                )
                for (i in block.indices) {
                    block[i] = (block[i].toInt() xor u[i].toInt()).toByte()
                }
            }

            val take = minOf(block.size, output.size - outputOffset)
            block.copyInto(
                destination = output,
                destinationOffset = outputOffset,
                endIndex = take,
            )
            outputOffset += take
        }
        return output
    }

    actual override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int,
    ): ByteArray = runBlocking {
        val type = when (mode) {
            Argon2Mode.ARGON2_D -> Argon2.Type.D
            Argon2Mode.ARGON2_I -> Argon2.Type.I
            Argon2Mode.ARGON2_ID -> Argon2.Type.ID
        }
        Argon2(
            version = Argon2.Version.V13,
            type = type,
            iterations = iterations,
            memory = memoryKb,
            parallelism = parallelism,
            hashSize = length,
        ).deriveKey(
            password = seed,
            salt = salt,
        )
    }

    actual override fun randomBytes(length: Int): ByteArray {
        require(length >= 0) {
            "Random output length must not be negative."
        }
        val output = ByteArray(length)
        if (output.isEmpty()) {
            return output
        }
        val status = output.usePinned { pinned ->
            SecRandomCopyBytes(
                rnd = kSecRandomDefault,
                count = output.size.convert(),
                bytes = pinned.addressOf(0),
            )
        }
        check(status == 0) {
            "Secure random generation failed with status $status."
        }
        return output
    }

    actual override fun randomInt(): Int {
        val bytes = randomBytes(Int.SIZE_BYTES)
        return bytes.fold(0) { acc, byte ->
            (acc shl 8) or (byte.toInt() and 0xff)
        }
    }

    actual override fun randomInt(until: Int): Int {
        require(until > 0) {
            "Random bound must be positive."
        }
        // Rejection sampling to avoid modulo bias: discard values that fall in
        // the final, incomplete `until`-sized window of the 2^32 range so every
        // residue is equally likely (matches java.util.Random#nextInt(bound)).
        val bound = until.toLong()
        val limit = TWO_POW_32 - TWO_POW_32 % bound
        while (true) {
            val value = randomInt().toLong() and 0xffffffffL
            if (value < limit) {
                return (value % bound).toInt()
            }
        }
    }

    actual override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray {
        val (nativeAlgorithm, outputSize) = when (algorithm) {
            CryptoHashAlgorithm.SHA_1 -> kCCHmacAlgSHA1 to 20
            CryptoHashAlgorithm.SHA_256 -> kCCHmacAlgSHA256 to 32
            CryptoHashAlgorithm.SHA_512 -> kCCHmacAlgSHA512 to 64
            CryptoHashAlgorithm.MD5 -> kCCHmacAlgMD5 to 16
        }
        val output = ByteArray(outputSize)
        key.usePinnedOrNull { keyPtr ->
            data.usePinnedOrNull { dataPtr ->
                output.usePinned { outputPinned ->
                    CCHmac(
                        algorithm = nativeAlgorithm,
                        key = keyPtr,
                        keyLength = key.size.convert(),
                        data = dataPtr,
                        dataLength = data.size.convert(),
                        macOut = outputPinned.addressOf(0),
                    )
                }
            }
        }
        return output
    }

    actual override fun sha1(data: ByteArray): ByteArray {
        val output = ByteArray(20)
        data.usePinnedOrNull { dataPtr ->
            output.usePinned { outputPinned ->
                CC_SHA1(
                    data = dataPtr,
                    len = data.size.convert(),
                    md = outputPinned.addressOf(0).reinterpret(),
                )
            }
        }
        return output
    }

    actual override fun sha256(data: ByteArray): ByteArray {
        val output = ByteArray(32)
        data.usePinnedOrNull { dataPtr ->
            output.usePinned { outputPinned ->
                CC_SHA256(
                    data = dataPtr,
                    len = data.size.convert(),
                    md = outputPinned.addressOf(0).reinterpret(),
                )
            }
        }
        return output
    }

    actual override fun sha512(data: ByteArray): ByteArray {
        val output = ByteArray(64)
        data.usePinnedOrNull { dataPtr ->
            output.usePinned { outputPinned ->
                CC_SHA512(
                    data = dataPtr,
                    len = data.size.convert(),
                    md = outputPinned.addressOf(0).reinterpret(),
                )
            }
        }
        return output
    }

    actual override fun md5(data: ByteArray): ByteArray {
        val output = ByteArray(16)
        data.usePinnedOrNull { dataPtr ->
            output.usePinned { outputPinned ->
                CC_MD5(
                    data = dataPtr,
                    len = data.size.convert(),
                    md = outputPinned.addressOf(0).reinterpret(),
                )
            }
        }
        return output
    }

    actual override fun aesEcbNoPaddingEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = aes(
        key = key,
        iv = null,
        data = data,
        operation = kCCEncrypt,
        options = kCCOptionECBMode,
        outputExtraSize = 0,
    )

    actual override fun aesCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = aes(
        key = key,
        iv = iv,
        data = data,
        operation = kCCEncrypt,
        options = kCCOptionPKCS7Padding,
        outputExtraSize = kCCBlockSizeAES128.toInt(),
    )

    actual override fun aesCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = aes(
        key = key,
        iv = iv,
        data = data,
        operation = kCCDecrypt,
        options = kCCOptionPKCS7Padding,
        outputExtraSize = kCCBlockSizeAES128.toInt(),
    )

    private fun aes(
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
        operation: UInt,
        options: UInt,
        outputExtraSize: Int,
    ): ByteArray = memScoped {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES requires a 16, 24, or 32-byte key."
        }
        require(iv == null || iv.size == kCCBlockSizeAES128.toInt()) {
            "AES-CBC requires a ${kCCBlockSizeAES128}-byte initialization vector."
        }

        val output = ByteArray(data.size + outputExtraSize)
        val outputMoved = alloc<size_tVar>()
        // Pin `data` and `output` via usePinnedOrNull: pinning a zero-length
        // ByteArray and taking addressOf(0) throws in Kotlin/Native, so empty
        // plaintext/output must pass a NULL pointer (CCCrypt accepts NULL with
        // length 0). `key` is always 16/24/32 bytes here, so it is never empty.
        val status = key.usePinned { keyPinned ->
            data.usePinnedOrNull { dataPtr ->
                output.usePinnedOrNull { outputPtr ->
                    (iv ?: ByteArray(0)).usePinnedOrNull { ivPtr ->
                        CCCrypt(
                            op = operation,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = keyPinned.addressOf(0),
                            keyLength = key.size.convert(),
                            iv = ivPtr,
                            dataIn = dataPtr,
                            dataInLength = data.size.convert(),
                            dataOut = outputPtr,
                            dataOutAvailable = output.size.convert(),
                            dataOutMoved = outputMoved.ptr,
                        )
                    }
                }
            }
        }
        check(status == kCCSuccess) {
            "AES operation failed with status $status."
        }
        val outputSizePointer: CPointer<ULongVar> = outputMoved.ptr.reinterpret()
        val outputSize = outputSizePointer[0].toInt()
        output.copyOf(outputSize)
    }

    private companion object {
        const val HMAC_SHA256_SIZE = 32
        const val INT_SIZE_BYTES = 4
        const val MAX_HKDF_SHA256_LENGTH = 255 * HMAC_SHA256_SIZE
        const val TWO_POW_32 = 0x100000000L
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
