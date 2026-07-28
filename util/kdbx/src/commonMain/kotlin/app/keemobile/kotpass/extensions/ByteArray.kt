package app.keemobile.kotpass.extensions

internal fun ByteArray.clear() {
    for (i in indices) this[i] = 0x0
}
