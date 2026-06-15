package com.artemchep.keyguard.common.service.text

interface Base32Service {
    //
    // Encoders
    //

    fun encode(
        text: String,
    ): ByteArray = encode(text.encodeToByteArray())

    fun encodeToString(
        text: String,
    ): String = encode(text).decodeToString()

    fun encode(
        bytes: ByteArray,
    ): ByteArray

    fun encodeToString(
        bytes: ByteArray,
    ): String = encode(bytes).decodeToString()

    //
    // Decoders
    //

    fun decode(
        text: String,
    ): ByteArray = decode(text.encodeToByteArray())

    fun decodeToString(
        text: String,
    ): String = decode(text).decodeToString()

    fun decode(
        bytes: ByteArray,
    ): ByteArray

    fun decodeToString(
        bytes: ByteArray,
    ): String = decode(bytes).decodeToString()
}
