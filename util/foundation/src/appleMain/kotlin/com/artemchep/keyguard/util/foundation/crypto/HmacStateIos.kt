package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.util.foundation.requireValidRange
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import platform.CoreCrypto.CCHmacAlgorithm
import platform.CoreCrypto.CCHmacContext
import platform.CoreCrypto.CCHmacFinal
import platform.CoreCrypto.CCHmacInit
import platform.CoreCrypto.CCHmacUpdate
import platform.CoreCrypto.kCCHmacAlgMD5
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA512

@OptIn(ExperimentalForeignApi::class)
actual fun createHmac(
    key: ByteArray,
    algorithm: CryptoHashAlgorithm,
): HmacState {
    val (nativeAlgorithm, outputSize) = algorithm.hmacAlgorithm()
    return IosHmacState(
        key = key,
        algorithm = nativeAlgorithm,
        outputSize = outputSize,
    )
}

/**
 * Owns the native [CCHmacContext] and its backing [Arena]. [release] is idempotent so
 * that [IosHmacState.doFinal], [IosHmacState.close], and the GC cleaner backstop can all
 * free the native memory without risking a double free.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosHmacContext {
    val arena = Arena()
    val context = arena.alloc<CCHmacContext>()
    private var released = false

    fun release() {
        if (released) {
            return
        }
        released = true
        arena.clear()
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private class IosHmacState(
    key: ByteArray,
    private val algorithm: CCHmacAlgorithm,
    private val outputSize: Int,
) : HmacState {
    private val native = IosHmacContext()

    // Backstop that frees the native context if the caller never calls doFinal()/close()
    // (e.g. update() threw, or the state was simply abandoned). Captures only `native`,
    // never `this`, so the cleaner does not keep the state alive.
    @Suppress("unused")
    private val cleaner = createCleaner(native, IosHmacContext::release)

    private var finalized = false

    init {
        key.usePinnedOrNull { keyPtr ->
            CCHmacInit(
                ctx = native.context.ptr,
                algorithm = algorithm,
                key = keyPtr,
                keyLength = key.size.convert(),
            )
        }
    }

    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!finalized) {
            "HMAC has already been finalized."
        }
        data.requireValidRange(offset, length)
        data.usePinnedRangeOrNull(offset, length) { dataPtr ->
            CCHmacUpdate(
                ctx = native.context.ptr,
                data = dataPtr,
                dataLength = length.convert(),
            )
        }
    }

    override fun doFinal(): ByteArray {
        check(!finalized) {
            "HMAC has already been finalized."
        }
        finalized = true
        val output = ByteArray(outputSize)
        try {
            output.usePinned { outputPinned ->
                CCHmacFinal(
                    ctx = native.context.ptr,
                    macOut = outputPinned.addressOf(0),
                )
            }
        } finally {
            native.release()
        }
        return output
    }

    override fun close() {
        native.release()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CryptoHashAlgorithm.hmacAlgorithm(): Pair<CCHmacAlgorithm, Int> = when (this) {
    CryptoHashAlgorithm.SHA_1 -> kCCHmacAlgSHA1 to 20
    CryptoHashAlgorithm.SHA_256 -> kCCHmacAlgSHA256 to 32
    CryptoHashAlgorithm.SHA_512 -> kCCHmacAlgSHA512 to 64
    CryptoHashAlgorithm.MD5 -> kCCHmacAlgMD5 to 16
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.usePinnedRangeOrNull(
    offset: Int,
    length: Int,
    block: (CPointer<ByteVar>?) -> T,
): T {
    if (length == 0) {
        return block(null)
    }
    return usePinned { pinned ->
        block(pinned.addressOf(offset))
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
