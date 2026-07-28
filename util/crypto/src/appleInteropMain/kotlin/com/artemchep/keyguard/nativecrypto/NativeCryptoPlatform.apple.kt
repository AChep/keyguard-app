@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.nativecrypto

import com.artemchep.keyguard.nativecrypto.ffi.KeyguardCryptoBuffer
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_abi_version
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_buffer_free
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_call
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_capabilities
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_random_int
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_stream_close
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_stream_finish
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_stream_open
import com.artemchep.keyguard.nativecrypto.ffi.keyguard_crypto_stream_update
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

internal actual object NativeCryptoPlatform : NativeCryptoBridge {
    actual override fun abiVersion(): Int = keyguard_crypto_abi_version().toInt()

    actual override fun capabilities(): Long = keyguard_crypto_capabilities().toLong()

    actual override fun randomInt(exclusiveUpperBound: Int): Long = memScoped {
        val output = alloc<IntVar>()
        output.value = 0
        val status = keyguard_crypto_random_int(
            exclusiveUpperBound.toUInt(),
            output.ptr,
        )
        packNativeCryptoIntResult(status, output.value)
    }

    actual override fun call(request: ByteArray): ByteArray = invokeWithInput(request) { pointer, size, output ->
        keyguard_crypto_call(pointer, size, output)
    }

    actual override fun streamOpen(request: ByteArray): ByteArray =
        invokeWithInput(request) { pointer, size, output ->
            keyguard_crypto_stream_open(pointer, size, output)
        }

    actual override fun streamUpdate(handle: Long, input: ByteArray): ByteArray =
        invokeWithInput(input) { pointer, size, output ->
            keyguard_crypto_stream_update(handle.toULong(), pointer, size, output)
        }

    actual override fun streamFinish(handle: Long): ByteArray = invokeWithoutInput { output ->
        keyguard_crypto_stream_finish(handle.toULong(), output)
    }

    actual override fun streamClose(handle: Long): ByteArray = invokeWithoutInput { output ->
        keyguard_crypto_stream_close(handle.toULong(), output)
    }

    actual override fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long {
        return encryptionKey.withNativePointer { encryptionKeyPointer, encryptionKeySize ->
            macKey.withNativePointer { macKeyPointer, macKeySize ->
                iv.withNativePointer { ivPointer, ivSize ->
                    plaintext.withNativePointer { plaintextPointer, plaintextSize ->
                        ciphertextOutput.withNativePointer { ciphertextPointer, ciphertextSize ->
                            macOutput.withNativePointer { macPointer, macSize ->
                                memScoped {
                                    val outputLength = alloc<ULongVar>()
                                    outputLength.value = 0uL
                                    val status = keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
                                        encryptionKeyPointer,
                                        encryptionKeySize,
                                        macKeyPointer,
                                        macKeySize,
                                        ivPointer,
                                        ivSize,
                                        plaintextPointer,
                                        plaintextSize,
                                        ciphertextPointer,
                                        ciphertextSize,
                                        macPointer,
                                        macSize,
                                        outputLength.ptr,
                                    )
                                    normalizeFastResult(
                                        status = status,
                                        outputLength = outputLength.value,
                                        maximumOutputLength = ciphertextOutput.size,
                                    ) {
                                        ciphertextOutput.fill(0)
                                        macOutput.fill(0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    actual override fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long {
        return encryptionKey.withNativePointer { encryptionKeyPointer, encryptionKeySize ->
            macKey.withNativePointer { macKeyPointer, macKeySize ->
                iv.withNativePointer { ivPointer, ivSize ->
                    ciphertext.withNativePointer { ciphertextPointer, ciphertextSize ->
                        expectedMac.withNativePointer { expectedMacPointer, expectedMacSize ->
                            plaintextOutput.withNativePointer { plaintextPointer, plaintextSize ->
                                memScoped {
                                    val outputLength = alloc<ULongVar>()
                                    outputLength.value = 0uL
                                    val status = keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
                                        encryptionKeyPointer,
                                        encryptionKeySize,
                                        macKeyPointer,
                                        macKeySize,
                                        ivPointer,
                                        ivSize,
                                        ciphertextPointer,
                                        ciphertextSize,
                                        expectedMacPointer,
                                        expectedMacSize,
                                        plaintextPointer,
                                        plaintextSize,
                                        outputLength.ptr,
                                    )
                                    normalizeFastResult(
                                        status = status,
                                        outputLength = outputLength.value,
                                        maximumOutputLength = plaintextOutput.size,
                                    ) {
                                        plaintextOutput.fill(0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private inline fun invokeWithInput(
        input: ByteArray,
        block: (CPointer<UByteVar>?, ULong, CPointer<KeyguardCryptoBuffer>) -> Int,
    ): ByteArray = if (input.isEmpty()) {
        invokeWithoutInput { output -> block(null, 0uL, output) }
    } else {
        input.usePinned { pinned ->
            invokeWithoutInput { output ->
                block(
                    pinned.addressOf(0).reinterpret<ByteVar>().reinterpret(),
                    input.size.convert(),
                    output,
                )
            }
        }
    }

    private inline fun invokeWithoutInput(
        block: (CPointer<KeyguardCryptoBuffer>) -> Int,
    ): ByteArray = memScoped {
        val outputPointer = allocArray<KeyguardCryptoBuffer>(1)
        val output = outputPointer.pointed
        output.ptr = null
        output.len = 0.convert()
        output.capacity = 0.convert()
        try {
            when (val transportCode = block(outputPointer)) {
                TRANSPORT_OK -> copyOutput(output)
                TRANSPORT_PANIC -> throw NativeCryptoPlatformException(NativeCryptoErrorCode.PANIC)
                else -> throw NativeCryptoPlatformException(
                    NativeCryptoErrorCode.INTERNAL,
                    IllegalStateException("Native crypto bridge code $transportCode"),
                )
            }
        } finally {
            keyguard_crypto_buffer_free(outputPointer)
        }
    }

    private fun copyOutput(output: KeyguardCryptoBuffer): ByteArray {
        val length = output.len.toLong()
        if (length < 0 || length > NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES) {
            throw NativeCryptoPlatformException(NativeCryptoErrorCode.RESOURCE_LIMIT)
        }
        if (length == 0L) return ByteArray(0)
        val data = output.ptr
            ?: throw NativeCryptoPlatformException(NativeCryptoErrorCode.MALFORMED_RESPONSE)
        return data.readBytes(length.toInt())
    }

    private inline fun <T> ByteArray.withNativePointer(
        block: (CPointer<UByteVar>?, ULong) -> T,
    ): T = if (isEmpty()) {
        block(null, 0uL)
    } else {
        usePinned { pinned ->
            block(
                pinned.addressOf(0).reinterpret<ByteVar>().reinterpret(),
                size.convert(),
            )
        }
    }

    private inline fun normalizeFastResult(
        status: Int,
        outputLength: ULong,
        maximumOutputLength: Int,
        clearOutputs: () -> Unit,
    ): Long {
        val validStatus = status in FAST_OK..FAST_MAX_WIRE_STATUS
        val length = outputLength.toLong()
        if (!validStatus || length !in 0L..maximumOutputLength.toLong() || status != FAST_OK && length != 0L) {
            clearOutputs()
            return packNativeCryptoFastResult(FAST_INTERNAL, 0)
        }
        if (status != FAST_OK) {
            clearOutputs()
            return packNativeCryptoFastResult(status, 0)
        }
        return packNativeCryptoFastResult(status, length.toInt())
    }

    private const val FAST_OK: Int = 0
    private const val FAST_INTERNAL: Int = 9
    private const val FAST_MAX_WIRE_STATUS: Int = 10
    private const val TRANSPORT_OK: Int = 0
    private const val TRANSPORT_PANIC: Int = -2
}
