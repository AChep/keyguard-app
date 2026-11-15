@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.artemchep.keyguard.nativecrypto

import com.artemchep.keyguard.nativecrypto.wasm.JsCryptoArray
import com.artemchep.keyguard.nativecrypto.wasm.JsCryptoArrays
import com.artemchep.keyguard.nativecrypto.wasm.copyAndClear
import com.artemchep.keyguard.nativecrypto.wasm.copyTo
import com.artemchep.keyguard.nativecrypto.wasm.intAt
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
import kotlin.js.asJsException

private const val WORD_BITS: Int = 32
private const val WORD_MASK: Long = 0xffff_ffffL

/** Initializes Rust/Wasm before using crypto from Kotlin/WasmGC. */
public suspend fun initializeNativeCryptoWasm() {
    suspendCoroutine<Unit> { continuation ->
        wasmInitialize().then(
            onFulfilled = {
                continuation.resume(Unit)
                null
            },
            onRejected = {
                continuation.resumeWithException(it.asJsException())
                null
            },
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
        return (words.intAt(1).toLong() shl WORD_BITS) or (words.intAt(0).toLong() and WORD_MASK)
    }

    actual override fun randomInt(exclusiveUpperBound: Int): Long {
        val result = wasmRandomInt(exclusiveUpperBound)
        return packNativeCryptoIntResult(result.intAt(0), result.intAt(1))
    }

    actual override fun call(request: ByteArray): ByteArray = JsCryptoArrays().use { arrays ->
        wasmCall(arrays.copy(request)).copyAndClear()
    }

    actual override fun streamOpen(request: ByteArray): ByteArray = JsCryptoArrays().use { arrays ->
        wasmStreamOpen(arrays.copy(request)).copyAndClear()
    }

    actual override fun streamUpdate(handle: Long, input: ByteArray): ByteArray = JsCryptoArrays().use { arrays ->
        wasmStreamUpdate(handle.toInt(), (handle ushr WORD_BITS).toInt(), arrays.copy(input)).copyAndClear()
    }

    actual override fun streamFinish(handle: Long): ByteArray =
        wasmStreamFinish(handle.toInt(), (handle ushr WORD_BITS).toInt()).copyAndClear()

    actual override fun streamClose(handle: Long): ByteArray =
        wasmStreamClose(handle.toInt(), (handle ushr WORD_BITS).toInt()).copyAndClear()

    actual override fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long = JsCryptoArrays().use { arrays ->
        val ciphertext = arrays.allocate(ciphertextOutput.size)
        val mac = arrays.allocate(macOutput.size)
        val result = wasmEncrypt(
            arrays.copy(encryptionKey), arrays.copy(macKey), arrays.copy(iv), arrays.copy(plaintext),
            ciphertext, mac,
        )
        ciphertext.copyTo(ciphertextOutput)
        mac.copyTo(macOutput)
        result.fastResult()
    }

    actual override fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long = JsCryptoArrays().use { arrays ->
        val plaintext = arrays.allocate(plaintextOutput.size)
        val result = wasmDecrypt(
            arrays.copy(encryptionKey), arrays.copy(macKey), arrays.copy(iv), arrays.copy(ciphertext),
            arrays.copy(expectedMac), plaintext,
        )
        plaintext.copyTo(plaintextOutput)
        result.fastResult()
    }
}

private fun JsCryptoArray.fastResult(): Long = packNativeCryptoFastResult(intAt(0), intAt(1))
