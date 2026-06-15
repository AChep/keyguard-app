package com.artemchep.keyguard.common.service.text

interface Base64Service {
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

fun Base64Service.url(base64: String): String = base64
    .replace('+', '-')
    .replace('/', '_')
    .replace("=", "")

fun Base64Service.decodeOrNull(
    text: String,
) = runCatching {
    decode(text)
}.getOrNull()

fun Base64Service.decodeToStringOrNull(
    text: String,
) = runCatching {
    decodeToString(text)
}.getOrNull()

fun Base64Service.decodeOrNull(
    bytes: ByteArray,
) = runCatching {
    decode(bytes)
}.getOrNull()

fun Base64Service.decodeToStringOrNull(
    bytes: ByteArray,
) = runCatching {
    decodeToString(bytes)
}.getOrNull()
