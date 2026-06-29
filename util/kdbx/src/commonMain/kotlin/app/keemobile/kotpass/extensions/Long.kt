package app.keemobile.kotpass.extensions

internal fun Long.toByteArray(): ByteArray {
    return byteArrayOf(
        this.toByte(),
        (this shr 8).toByte(),
        (this shr 16).toByte(),
        (this shr 24).toByte(),
        (this shr 32).toByte(),
        (this shr 40).toByte(),
        (this shr 48).toByte(),
        (this shr 56).toByte()
    )
}

internal fun Long.Companion.fromByteArray(bytes: ByteArray): Long {
    return (
        bytes[7].toLong() shl 56
            or (bytes[6].toLong() and 0xff shl 48)
            or (bytes[5].toLong() and 0xff shl 40)
            or (bytes[4].toLong() and 0xff shl 32)
            or (bytes[3].toLong() and 0xff shl 24)
            or (bytes[2].toLong() and 0xff shl 16)
            or (bytes[1].toLong() and 0xff shl 8)
            or (bytes[0].toLong() and 0xff)
        )
}
