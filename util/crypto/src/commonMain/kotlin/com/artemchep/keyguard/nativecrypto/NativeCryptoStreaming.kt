package com.artemchep.keyguard.nativecrypto

/** Maximum input accepted by one native stream update call. */
const val NATIVE_CRYPTO_STREAM_CHUNK_BYTES: Int = 64 * 1024
internal const val NATIVE_CRYPTO_INLINE_DATA_BYTES: Int = 15 * 1024 * 1024

/**
 * Largest combined HMAC key and input size that uses the one-shot protobuf operation.
 *
 * On macOS arm64 with JDK 21 and a 32-byte key, one-shot vs streaming median latency was
 * 4.80 vs 5.94 us at 2 KiB, 8.10 vs 7.61 us at 4 KiB, and 15.45 vs 11.39 us at 8 KiB.
 * The 8 KiB cutoff keeps the switch at the first measured size with a clear streaming advantage.
 */
internal const val NATIVE_CRYPTO_HMAC_ONE_SHOT_MAX_BYTES: Int = 8 * 1024

internal fun ByteArray.requireNativeCryptoOutputSize(
    operation: String,
    expectedSize: Int,
): ByteArray {
    if (size != expectedSize) {
        fill(0)
        throw NativeCryptoException(
            operation = operation,
            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
        )
    }
    return this
}

internal fun NativeCryptoSession.withExpectedFinalOutputSize(
    operation: String,
    expectedSize: Int,
): NativeCryptoSession = ExpectedFinalOutputSizeSession(
    delegate = this,
    operation = operation,
    expectedSize = expectedSize,
)

/**
 * Feeds caller-owned input to a native session and stages every output byte until finish succeeds.
 */
internal fun collectNativeStream(
    session: NativeCryptoSession,
    input: ByteArray,
    chunkSize: Int = NATIVE_CRYPTO_STREAM_CHUNK_BYTES,
): ByteArray {
    require(chunkSize in 1..NATIVE_CRYPTO_STREAM_CHUNK_BYTES) {
        "Native crypto stream chunk size must be in 1..$NATIVE_CRYPTO_STREAM_CHUNK_BYTES"
    }
    val stagedOutputs = mutableListOf<ByteArray>()
    var totalOutputSize = 0L
    var primaryFailure: Throwable? = null
    var resultData: ByteArray? = null
    val result = try {
        var offset = 0
        while (offset < input.size) {
            val length = minOf(chunkSize, input.size - offset)
            stageOutput(
                operation = "stream.update",
                output = session.update(input, offset, length),
                stagedOutputs = stagedOutputs,
                previousTotal = totalOutputSize,
            ).also { totalOutputSize = it }
            offset += length
        }
        stageOutput(
            operation = "stream.update",
            output = session.finish(),
            stagedOutputs = stagedOutputs,
            previousTotal = totalOutputSize,
        ).also { totalOutputSize = it }
        mergeStagedOutputs(stagedOutputs, totalOutputSize.toInt())
            .also { resultData = it }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        stagedOutputs.forEach { output -> output.fill(0) }
        try {
            session.close()
        } catch (closeFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(closeFailure)
            } else {
                resultData?.fill(0)
                primaryFailure = closeFailure
            }
        }
    }
    primaryFailure?.let { throw it }
    return result
}

/** Collects authenticated-or-public output directly when its exact final size is known. */
internal fun collectNativeStreamToExpectedSize(
    session: NativeCryptoSession,
    input: ByteArray,
    expectedOutputSize: Int,
    operation: String,
    chunkSize: Int = NATIVE_CRYPTO_STREAM_CHUNK_BYTES,
): ByteArray {
    require(expectedOutputSize >= 0) { "Expected native stream output size must not be negative" }
    require(chunkSize in 1..NATIVE_CRYPTO_STREAM_CHUNK_BYTES) {
        "Native crypto stream chunk size must be in 1..$NATIVE_CRYPTO_STREAM_CHUNK_BYTES"
    }
    val result = ByteArray(expectedOutputSize)
    var outputOffset = 0
    var primaryFailure: Throwable? = null
    var deferredCloseFailure: Throwable? = null
    try {
        fun append(output: ByteArray) {
            try {
                if (output.size > result.size - outputOffset) {
                    throw NativeCryptoException(
                        operation = operation,
                        code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
                    )
                }
                output.copyInto(result, destinationOffset = outputOffset)
                outputOffset += output.size
            } finally {
                output.fill(0)
            }
        }

        var inputOffset = 0
        while (inputOffset < input.size) {
            val length = minOf(chunkSize, input.size - inputOffset)
            append(session.update(input, inputOffset, length))
            inputOffset += length
        }
        append(session.finish())
        if (outputOffset != result.size) {
            throw NativeCryptoException(
                operation = operation,
                code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
            )
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        result.fill(0)
        throw failure
    } finally {
        try {
            session.close()
        } catch (closeFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(closeFailure)
            } else {
                result.fill(0)
                deferredCloseFailure = closeFailure
            }
        }
    }
    deferredCloseFailure?.let { throw it }
    return result
}

/**
 * Walks this buffer in transport-sized chunks, invoking [block] with the
 * offset and length of each. The buffer itself is never copied.
 */
internal inline fun ByteArray.forEachStreamChunk(
    block: (offset: Int, length: Int) -> Unit,
) {
    var offset = 0
    while (offset < size) {
        val length = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, size - offset)
        block(offset, length)
        offset += length
    }
}

internal fun stageOutput(
    operation: String,
    output: ByteArray,
    stagedOutputs: MutableList<ByteArray>,
    previousTotal: Long,
): Long {
    val total = previousTotal + output.size
    if (total > Int.MAX_VALUE) {
        output.fill(0)
        throw NativeCryptoException(
            operation = operation,
            code = NativeCryptoErrorCode.RESOURCE_LIMIT,
        )
    }
    if (output.isNotEmpty()) {
        stagedOutputs += output
    }
    return total
}

internal fun mergeStagedOutputs(
    stagedOutputs: List<ByteArray>,
    totalOutputSize: Int,
): ByteArray {
    val result = ByteArray(totalOutputSize)
    var offset = 0
    stagedOutputs.forEach { output ->
        output.copyInto(result, destinationOffset = offset)
        offset += output.size
    }
    return result
}

private class ExpectedFinalOutputSizeSession(
    private val delegate: NativeCryptoSession,
    private val operation: String,
    private val expectedSize: Int,
) : NativeCryptoSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): ByteArray = delegate.finish()
        .requireNativeCryptoOutputSize(operation, expectedSize)

    override fun close() {
        delegate.close()
    }
}
