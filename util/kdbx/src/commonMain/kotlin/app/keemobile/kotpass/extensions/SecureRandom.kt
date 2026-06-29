package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.cryptography.SecureRandom
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal fun SecureRandom.nextByteString(length: Int): ByteString {
    return ByteArray(length)
        .apply { nextBytes(this) }
        .toByteString()
}
