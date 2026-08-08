package com.artemchep.keyguard.common.service.crypto

import kotlin.jvm.JvmInline

/**
 * Platform-neutral interpretation of the OpenPGP literal data packet format.
 *
 * MIME type and charset availability are deliberately left to consumers. The format only
 * describes what the packet declares.
 */
enum class GpgOpenPgpLiteralFormat {
    BINARY,
    TEXT,
    UTF8,
    MIME,
    UNKNOWN,
}

class GpgOpenPgpLiteralMetadata(
    val fileName: ByteArray,
    val format: Int,
    val modificationTimeEpochSeconds: Long,
    val originalSize: Long,
)

/** A validated, size-bounded OpenPGP literal data packet file name. */
@JvmInline
value class GpgOpenPgpLiteralFileName private constructor(
    val value: String,
) {
    companion object {
        /** Converts an untrusted file name to a value safe to write into a literal packet. */
        fun fromUntrusted(value: String?): GpgOpenPgpLiteralFileName =
            GpgOpenPgpLiteralFileName(
                GpgOpenPgpLiteralFileNamePolicy.forWriting(value),
            )
    }
}

data class GpgOpenPgpNormalizedLiteralMetadata(
    val fileName: String,
    val format: GpgOpenPgpLiteralFormat,
    val modificationTimeEpochSeconds: Long,
    val originalSize: Long,
)

/**
 * Decodes and bounds untrusted literal packet metadata before it reaches a platform adapter.
 */
fun GpgOpenPgpLiteralMetadata.normalized(): GpgOpenPgpNormalizedLiteralMetadata =
    GpgOpenPgpNormalizedLiteralMetadata(
        fileName = GpgOpenPgpLiteralFileNamePolicy.decodeFromPacket(fileName),
        format = when (format) {
            BINARY_LITERAL_FORMAT -> GpgOpenPgpLiteralFormat.BINARY
            TEXT_LITERAL_FORMAT -> GpgOpenPgpLiteralFormat.TEXT
            UTF8_LITERAL_FORMAT -> GpgOpenPgpLiteralFormat.UTF8
            MIME_LITERAL_FORMAT -> GpgOpenPgpLiteralFormat.MIME
            else -> GpgOpenPgpLiteralFormat.UNKNOWN
        },
        modificationTimeEpochSeconds = modificationTimeEpochSeconds
            .takeIf { it in 0L..MAX_EPOCH_SECONDS_WITH_MILLISECONDS }
            ?: 0L,
        originalSize = originalSize.coerceAtLeast(0L),
    )

private object GpgOpenPgpLiteralFileNamePolicy {
    fun forWriting(value: String?): String {
        val candidate = value
            ?.trim()
            ?.takeIf(String::isSafeGpgOpenPgpLiteralFileName)
            ?: return CONSOLE_LITERAL_FILE_NAME
        val encoded = candidate.encodeToByteArray()
        return try {
            if (encoded.size <= MAX_LITERAL_FILE_NAME_BYTES) {
                candidate
            } else {
                encoded.decodeValidUtf8Prefix(MAX_LITERAL_FILE_NAME_BYTES)
                    ?: CONSOLE_LITERAL_FILE_NAME
            }
        } finally {
            encoded.fill(0)
        }
    }

    fun decodeFromPacket(value: ByteArray): String {
        if (value.size > MAX_LITERAL_FILE_NAME_BYTES) {
            return ""
        }
        return runCatching {
            value.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
            ?.takeIf(String::isSafeGpgOpenPgpLiteralFileName)
            .orEmpty()
    }
}

private fun String.isSafeGpgOpenPgpLiteralFileName(): Boolean =
    isNotBlank() &&
        this != "." &&
        this != ".." &&
        '/' !in this &&
        '\\' !in this &&
        none(Char::isUnsafeGpgOpenPgpLiteralFileNameChar)

/**
 * True for characters that must never appear in an OpenPGP literal file
 * name. ISO control characters enable log and status-line injection when
 * the name is rendered (GnuPG CVE-2018-12020 "SigSpoof"); Unicode
 * directional formatting characters enable file extension spoofing.
 */
private fun Char.isUnsafeGpgOpenPgpLiteralFileNameChar(): Boolean =
    isISOControl() ||
        this == '\u061C' ||
        this in '\u200E'..'\u200F' ||
        this in '\u202A'..'\u202E' ||
        this in '\u2066'..'\u2069'

private fun ByteArray.decodeValidUtf8Prefix(maxBytes: Int): String? {
    var length = minOf(size, maxBytes)
    while (length > 0) {
        val decoded = runCatching {
            copyOf(length).decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
        if (decoded != null) {
            return decoded
        }
        length -= 1
    }
    return null
}

private const val BINARY_LITERAL_FORMAT = 'b'.code
private const val TEXT_LITERAL_FORMAT = 't'.code
private const val UTF8_LITERAL_FORMAT = 'u'.code
private const val MIME_LITERAL_FORMAT = 'm'.code
private const val CONSOLE_LITERAL_FILE_NAME = "_CONSOLE"
private const val MAX_LITERAL_FILE_NAME_BYTES = 255
private const val MAX_EPOCH_SECONDS_WITH_MILLISECONDS = Long.MAX_VALUE / 1000L
