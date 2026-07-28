package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readLine
import kotlinx.io.write
import kotlinx.io.writeString

fun String.toSource(): Source = Buffer().apply {
    writeString(this@toSource)
}

fun ByteArray.toSource(): Source = Buffer().apply {
    write(this@toSource)
}

fun Source.readByteArrayAndClose(): ByteArray = try {
    readByteArray()
} finally {
    close()
}

/**
 * Consumes this source through a reusable buffer which is erased before returning.
 * The source remains owned by the caller.
 */
fun Source.consumeWithErasedBuffer(
    bufferSize: Int = DEFAULT_ERASED_BUFFER_BYTES,
    consume: (ByteArray, Int) -> Unit,
) {
    require(bufferSize > 0) { "Buffer size must be positive" }
    val buffer = ByteArray(bufferSize)
    var consecutiveZeroReads = 0
    try {
        while (true) {
            val read = readAtMostTo(buffer)
            if (read < 0) break
            if (read == 0) {
                consecutiveZeroReads += 1
                if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                    throw IOException("Source made no progress while reading")
                }
                continue
            }
            consecutiveZeroReads = 0
            consume(buffer, read)
        }
    } finally {
        buffer.fill(0)
    }
}

inline fun <T> Source.useLines(
    block: (Sequence<String>) -> T,
): T = use { source ->
    block(generateSequence { source.readLine() })
}

fun Sink.writeByteArray(
    data: ByteArray,
) {
    write(data)
    flush()
}

fun Sink.writeText(
    data: String,
) {
    writeString(data)
    flush()
}

private const val DEFAULT_ERASED_BUFFER_BYTES = 64 * 1024

private const val MAX_CONSECUTIVE_ZERO_READS = 16
