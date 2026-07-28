package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.io.BufferedStream
import app.keemobile.kotpass.io.RealBufferedStream
import app.keemobile.kotpass.io.TeeBufferedStream
import okio.Buffer
import okio.Source

internal fun Source.bufferStream(): BufferedStream {
    return RealBufferedStream(this)
}

internal fun Source.teeBufferStream(mirrorBuffer: Buffer): BufferedStream {
    return TeeBufferedStream(this, mirrorBuffer)
}
