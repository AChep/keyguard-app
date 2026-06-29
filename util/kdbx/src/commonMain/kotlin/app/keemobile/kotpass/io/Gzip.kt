package app.keemobile.kotpass.io

import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

internal fun ByteArray.gzip(): ByteArray {
    val output = Buffer()
    val sink = GzipSink(output).buffer()
    try {
        sink.write(this)
    } finally {
        sink.close()
    }
    return output.readByteArray()
}

internal fun ByteArray.gunzip(): ByteArray {
    val input = Buffer().write(this)
    val source = GzipSource(input).buffer()
    return try {
        source.readByteArray()
    } finally {
        source.close()
    }
}
