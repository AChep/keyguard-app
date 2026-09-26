package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.BufferedStream
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal fun authenticateVer4Header(
    source: BufferedStream,
    rawHeaderData: ByteString,
    validateHashes: Boolean,
    masterSeed: ByteArray,
    transformedKey: ByteArray,
) {
    val expectedSha256 = source.readByteString(32)
    val expectedHmacSha256 = source.readByteString(32)
    if (validateHashes) {
        if (rawHeaderData.sha256() != expectedSha256) {
            throw FormatError.InvalidHeader("Header's Sha256 does not match.")
        }
        val hmacKey = KeyTransform.hmacKey(masterSeed, transformedKey)
        try {
            if (rawHeaderData.hmacSha256(hmacKey.toByteString()) != expectedHmacSha256) {
                throw CryptoError.InvalidKey("Wrong key used for decryption.")
            }
        } finally {
            hmacKey.fill(0)
        }
    }
}
