package com.artemchep.keyguard.nativecrypto

import com.artemchep.keyguard.nativecrypto.wasm.wasmAbiVersion
import com.artemchep.keyguard.nativecrypto.wasm.wasmCall
import com.artemchep.keyguard.nativecrypto.wasm.wasmCapabilities
import com.artemchep.keyguard.nativecrypto.wasm.wasmDecrypt
import com.artemchep.keyguard.nativecrypto.wasm.wasmEncrypt
import com.artemchep.keyguard.nativecrypto.wasm.wasmInitialize
import com.artemchep.keyguard.nativecrypto.wasm.wasmRandomInt
import com.artemchep.keyguard.nativecrypto.wasm.wasmStreamClose
import com.artemchep.keyguard.nativecrypto.wasm.wasmStreamFinish
import com.artemchep.keyguard.nativecrypto.wasm.wasmStreamOpen
import com.artemchep.keyguard.nativecrypto.wasm.wasmStreamUpdate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val WORD_BITS: Int = 32
private const val WORD_MASK: Long = 0xffff_ffffL

/** Initializes Rust/Wasm before using crypto from Kotlin/JS. */
public suspend fun initializeNativeCryptoWasm() {
    suspendCoroutine<Unit> { continuation ->
        wasmInitialize().then(
            onFulfilled = { continuation.resume(Unit) },
            onRejected = { continuation.resumeWithException(it) },
        )
    }
    NativeCrypto.ensureReady()
}

internal actual object NativeCryptoPlatform : NativeCryptoBridge {
    actual override val requiredCapabilitiesMask: Long
        get() = webCryptoCapabilitiesMask

    actual override fun abiVersion(): Int = wasmAbiVersion()

    actual override fun capabilities(): Long {
        val words = wasmCapabilities()
        return (words[1].toLong() shl WORD_BITS) or (words[0].toLong() and WORD_MASK)
    }

    actual override fun randomInt(exclusiveUpperBound: Int): Long {
        val result = wasmRandomInt(exclusiveUpperBound)
        return packNativeCryptoIntResult(result[0], result[1])
    }

    actual override fun call(request: ByteArray): ByteArray = wasmCall(request)

    actual override fun streamOpen(request: ByteArray): ByteArray = wasmStreamOpen(request)

    actual override fun streamUpdate(handle: Long, input: ByteArray): ByteArray =
        wasmStreamUpdate(handle.toInt(), (handle ushr WORD_BITS).toInt(), input)

    actual override fun streamFinish(handle: Long): ByteArray =
        wasmStreamFinish(handle.toInt(), (handle ushr WORD_BITS).toInt())

    actual override fun streamClose(handle: Long): ByteArray =
        wasmStreamClose(handle.toInt(), (handle ushr WORD_BITS).toInt())

    actual override fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long {
        val result = wasmEncrypt(encryptionKey, macKey, iv, plaintext, ciphertextOutput, macOutput)
        return packNativeCryptoFastResult(result[0], result[1])
    }

    actual override fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long {
        val result = wasmDecrypt(encryptionKey, macKey, iv, ciphertext, expectedMac, plaintextOutput)
        return packNativeCryptoFastResult(result[0], result[1])
    }
}
