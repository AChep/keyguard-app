package app.keemobile.kotpass.resources

import app.keemobile.kotpass.io.decodeHexToArray

internal object Argon2Res {
    val TestSalt: ByteArray = "02020202020202020202020202020202".decodeHexToArray()
    val TestSecret: ByteArray = "0303030303030303".decodeHexToArray()
    val TestAdditional: ByteArray = "040404040404040404040404".decodeHexToArray()
    val TestPassword: ByteArray =
        "0101010101010101010101010101010101010101010101010101010101010101".decodeHexToArray()
}
