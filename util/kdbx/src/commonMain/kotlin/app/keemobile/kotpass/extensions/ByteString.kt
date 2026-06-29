package app.keemobile.kotpass.extensions

import okio.ByteString
import kotlin.uuid.Uuid

internal fun ByteString.asIntLe(): Int {
    require(size >= Int.SIZE_BYTES)
    return (this[0].toInt() and 0xff) or
            ((this[1].toInt() and 0xff) shl 8) or
            ((this[2].toInt() and 0xff) shl 16) or
            ((this[3].toInt() and 0xff) shl 24)
}

internal fun ByteString.asLongLe(): Long {
    require(size >= Long.SIZE_BYTES)
    var result = 0L
    for (i in 0 until Long.SIZE_BYTES) {
        result = result or ((this[i].toLong() and 0xffL) shl (i * 8))
    }
    return result
}

internal fun ByteString.asUuid(): Uuid = Uuid.fromByteArray(toByteArray())
