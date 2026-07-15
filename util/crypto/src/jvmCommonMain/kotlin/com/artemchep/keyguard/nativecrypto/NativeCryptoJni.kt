package com.artemchep.keyguard.nativecrypto

internal object NativeCryptoJni {
    external fun abiVersion(): Int

    external fun capabilities(): Long

    external fun randomInt(exclusiveUpperBound: Int): Long

    external fun call(request: ByteArray): ByteArray

    external fun streamOpen(request: ByteArray): ByteArray

    external fun streamUpdate(handle: Long, input: ByteArray): ByteArray

    external fun streamFinish(handle: Long): ByteArray

    external fun streamClose(handle: Long): ByteArray

    external fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long

    external fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long
}
