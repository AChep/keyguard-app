package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import diglol.crypto.Argon2
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgMD5
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA512
import platform.Foundation.NSUUID
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
class CryptoGeneratorIos : CryptoGenerator {
    companion object {
        private const val DEFAULT_ARGON_HASH_LENGTH = 32
        private const val HMAC_SHA256_SIZE = 32
        private const val INT_SIZE_BYTES = 4
    }

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray {
        require(length >= 0) {
            "HKDF output length must not be negative."
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

    override fun pbkdf2(
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

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
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
            hashSize = DEFAULT_ARGON_HASH_LENGTH,
        ).deriveKey(
            password = seed,
            salt = salt,
        )
    }

    override fun seed(length: Int): ByteArray {
        val output = ByteArray(length)
        output.usePinned { pinned ->
            val status = SecRandomCopyBytes(
                rnd = kSecRandomDefault,
                count = length.convert(),
                bytes = pinned.addressOf(0),
            )
            check(status == 0) {
                "Secure random generation failed with status $status."
            }
        }
        return output
    }

    override fun hmac(
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

    override fun hashSha1(data: ByteArray): ByteArray {
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

    override fun hashSha256(data: ByteArray): ByteArray {
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

    override fun hashMd5(data: ByteArray): ByteArray {
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

    override fun uuid(): String = NSUUID().UUIDString.lowercase()

    override fun random(): Int {
        val bytes = seed(Int.SIZE_BYTES)
        return bytes.fold(0) { acc, byte ->
            (acc shl 8) or (byte.toInt() and 0xff)
        }
    }

    override fun random(range: IntRange): Int {
        val size = range.last.toLong() - range.first.toLong() + 1L
        require(size > 0L) {
            "Random range must not be empty."
        }
        val value = random().toLong() and 0xffffffffL
        return range.first + (value % size).toInt()
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
