package com.artemchep.keyguard.common.io

import kotlinx.io.Buffer
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
