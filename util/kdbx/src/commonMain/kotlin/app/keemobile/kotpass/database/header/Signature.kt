package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.io.BufferedStream
import okio.BufferedSink
import okio.ByteString

/**
 * This signature is used to identify the file type and version.
 */
class Signature(val base: ByteString, val secondary: ByteString) {
    internal fun writeTo(sink: BufferedSink) = with(sink) {
        write(base)
        write(secondary)
    }

    companion object {
        val Base = Const.bytes(0x03, 0xD9, 0xA2, 0x9A)
        val Secondary = Const.bytes(0x67, 0xFB, 0x4B, 0xB5)
        val Default = Signature(Base, Secondary)

        internal fun readFrom(source: BufferedStream) = Signature(
            base = source.readByteString(4),
            secondary = source.readByteString(4)
        )
    }
}
