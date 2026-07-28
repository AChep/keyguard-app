package com.artemchep.keyguard.nativecrypto

internal interface NativeCryptoBridge {
    fun abiVersion(): Int

    fun capabilities(): Long

    fun randomInt(exclusiveUpperBound: Int): Long

    fun call(request: ByteArray): ByteArray

    fun streamOpen(request: ByteArray): ByteArray

    fun streamUpdate(handle: Long, input: ByteArray): ByteArray

    fun streamFinish(handle: Long): ByteArray

    fun streamClose(handle: Long): ByteArray

    fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long

    fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long
}

internal expect object NativeCryptoPlatform : NativeCryptoBridge {
    override fun abiVersion(): Int

    override fun capabilities(): Long

    override fun randomInt(exclusiveUpperBound: Int): Long

    override fun call(request: ByteArray): ByteArray

    override fun streamOpen(request: ByteArray): ByteArray

    override fun streamUpdate(handle: Long, input: ByteArray): ByteArray

    override fun streamFinish(handle: Long): ByteArray

    override fun streamClose(handle: Long): ByteArray

    override fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long

    override fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long
}

internal fun packNativeCryptoFastResult(
    statusCode: Int,
    outputLength: Int,
): Long =
    (statusCode.toLong() shl NATIVE_CRYPTO_FAST_RESULT_STATUS_SHIFT) or
        (outputLength.toLong() and NATIVE_CRYPTO_FAST_RESULT_LENGTH_MASK)

internal fun packNativeCryptoIntResult(
    statusCode: Int,
    value: Int,
): Long =
    (statusCode.toLong() shl NATIVE_CRYPTO_FAST_RESULT_STATUS_SHIFT) or
        (value.toLong() and NATIVE_CRYPTO_FAST_RESULT_LENGTH_MASK)

internal fun Long.nativeCryptoFastStatusCode(): Int =
    (this ushr NATIVE_CRYPTO_FAST_RESULT_STATUS_SHIFT).toInt()

internal fun Long.nativeCryptoFastOutputLength(): Int = toInt()

internal fun Long.nativeCryptoIntValue(): Int = toInt()

private const val NATIVE_CRYPTO_FAST_RESULT_STATUS_SHIFT: Int = 32
private const val NATIVE_CRYPTO_FAST_RESULT_LENGTH_MASK: Long = 0xffff_ffffL
