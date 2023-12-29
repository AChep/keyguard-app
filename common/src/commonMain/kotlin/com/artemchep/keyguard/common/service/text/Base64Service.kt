package com.artemchep.keyguard.common.service.text

interface Base64Service {
    //
    // Encoders
    //

    fun encode(
        text: String,
    ): ByteArray = encode(text.toByteArray())

    fun encodeToString(
        text: String,
    ): String = encode(text).let(::String)

    fun encode(
        bytes: ByteArray,
    ): ByteArray

    fun encodeToString(
        bytes: ByteArray,
    ): String = encode(bytes).let(::String)

    //
    // Decoders
    //

    fun decode(
        text: String,
    ): ByteArray = decode(text.toByteArray())

    fun decodeToString(
        text: String,
    ): String = decode(text).let(::String)

    fun decode(
        bytes: ByteArray,
    ): ByteArray

    fun decodeToString(
        bytes: ByteArray,
    ): String = decode(bytes).let(::String)
}

fun Base64Service.url(base64: String): String = base64
    .replace('+', '-')
    .replace('/', '_')
    .replace("=", "")
