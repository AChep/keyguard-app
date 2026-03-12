package com.artemchep.keyguard.common.io

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.InputStream
import java.io.OutputStream

actual fun Source.toInputStream(): InputStream = asInputStream()

actual fun Sink.toOutputStream(): OutputStream = asOutputStream()

suspend inline fun <T> OutputStream.useBufferedSink(
    block: suspend (Sink) -> T,
): T {
    val sink = asSink().buffered()
    return sink.use { sink ->
        val result = block(sink)
        result
    }
}

suspend inline fun <T> OutputStream.withBufferedSink(
    block: suspend (Sink) -> T,
): T {
    val sink = asSink().buffered()
    val result = block(sink)
    sink.flush()
    return result
}
