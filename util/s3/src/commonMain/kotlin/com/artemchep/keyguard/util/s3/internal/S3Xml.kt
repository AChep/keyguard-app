package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3Object
import com.artemchep.keyguard.util.xml.XmlNode
import com.artemchep.keyguard.util.xml.XmlParser
import kotlin.text.CharacterCodingException
import kotlin.time.Instant

internal data class S3ListBucketResult(
    val isTruncated: Boolean,
    val objects: List<S3Object>,
    val commonPrefixes: List<String>,
    val nextContinuationToken: String?,
    /**
     * The marker of the next page, when a server answered with a version 1
     * listing: it does not support continuation tokens.
     */
    val nextMarker: String? = null,
)

internal data class S3ErrorBody(
    val code: String?,
    val message: String?,
    val region: String?,
    val serverTime: Instant?,
)

/*
 * Elements are matched by local name only: AWS qualifies them with the
 * 2006-03-01 namespace, while some compatible servers omit it.
 */
internal object S3Xml {
    fun parseListBucketResult(
        xml: String,
    ): S3ListBucketResult {
        val root = XmlParser.parse(xml)
        require(root.name.local == "ListBucketResult") {
            "Expected ListBucketResult root element."
        }
        // With encoding-type=url the server percent-encodes keys and
        // prefixes, but only when it echoes the encoding type back.
        val urlEncoded = root.childText("EncodingType")
            ?.equals("url", ignoreCase = true) == true
        val decode: (String) -> String = if (urlEncoded) {
            { value -> decodeUrlEncodedValue(value) }
        } else {
            { value -> value }
        }
        val objects = root.children
            .filter { it.name.local == "Contents" }
            .map { contents ->
                val key = contents.childText("Key")
                    ?: throw IllegalArgumentException("ListBucketResult entry has no Key.")
                S3Object(
                    key = decode(key),
                    size = contents.childText("Size")
                        ?.trim()
                        ?.toLongOrNull(),
                    lastModified = contents.childText("LastModified")
                        ?.trim()
                        ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    etag = contents.childText("ETag")?.trimToNull(),
                )
            }
        val commonPrefixes = root.children
            .filter { it.name.local == "CommonPrefixes" }
            .mapNotNull { it.childText("Prefix") }
            .map(decode)
        // Only a version 1 listing has a Marker. Without a delimiter it
        // continues after the last key, otherwise after NextMarker.
        val nextMarker = if (root.children.any { it.name.local == "Marker" }) {
            root.childText("NextMarker")
                ?.let(decode)
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(objects.lastOrNull()?.key, commonPrefixes.lastOrNull())
                    .maxWithOrNull(::compareUtf8)
        } else {
            null
        }
        return S3ListBucketResult(
            // AWS uses true/false; compatible servers may use the other
            // XML Schema boolean spellings. Missing or unknown is not false.
            isTruncated = when (root.childText("IsTruncated")?.trim()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> throw IllegalArgumentException("ListBucketResult has no valid IsTruncated value.")
            },
            objects = objects,
            commonPrefixes = commonPrefixes,
            nextContinuationToken = root.childText("NextContinuationToken")
                ?.takeIf { it.isNotEmpty() },
            nextMarker = nextMarker,
        )
    }

    /**
     * Decodes `+` as a space and `%XX` escapes as UTF-8. Invalid UTF-8 fails
     * instead of becoming U+FFFD, which would name another object.
     */
    private fun decodeUrlEncodedValue(
        value: String,
    ): String = buildString {
        var index = 0
        while (index < value.length) {
            when (val char = value[index]) {
                '+' -> {
                    append(' ')
                    index += 1
                }

                '%' -> {
                    val bytes = mutableListOf<Byte>()
                    while (index < value.length && value[index] == '%') {
                        val byte = value
                            .takeIf { index + PERCENT_ESCAPE_LENGTH <= value.length }
                            ?.substring(index + 1, index + PERCENT_ESCAPE_LENGTH)
                            ?.takeIf { hex -> hex.all { it.isHexDigit() } }
                            ?.toInt(radix = HEX_RADIX)
                            ?: throw IllegalArgumentException("ListBucketResult has an invalid url-encoded value.")
                        bytes += byte.toByte()
                        index += PERCENT_ESCAPE_LENGTH
                    }
                    try {
                        append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
                    } catch (e: CharacterCodingException) {
                        throw IllegalArgumentException("ListBucketResult has a url-encoded value that is not UTF-8.", e)
                    }
                }

                else -> {
                    append(char)
                    index += 1
                }
            }
        }
    }

    fun parseErrorOrNull(
        xml: String,
    ): S3ErrorBody? {
        val root = xml
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { XmlParser.parse(it, requireComplete = false) }.getOrNull() }
            ?.takeIf { it.name.local == "Error" }
            ?: return null
        return S3ErrorBody(
            code = root.childText("Code")?.trimToNull(),
            message = root.childText("Message")?.trimToNull(),
            region = root.childText("Region")?.trimToNull(),
            serverTime = root.childText("ServerTime")
                ?.trim()
                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }
}

internal fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

/** Compares strings in the UTF-8 byte order that S3 lists keys in. */
private fun compareUtf8(
    a: String,
    b: String,
): Int {
    val left = a.encodeToByteArray()
    val right = b.encodeToByteArray()
    for (index in 0 until minOf(left.size, right.size)) {
        val diff = left[index].toUByte().compareTo(right[index].toUByte())
        if (diff != 0) {
            return diff
        }
    }
    return left.size - right.size
}

private const val PERCENT_ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun XmlNode.childText(
    local: String,
): String? = children
    .firstOrNull { it.name.local == local }
    ?.directTextContent
