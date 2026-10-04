package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Checks the headers of a successful GET and returns the number of body
 * bytes to expect, or null when the server did not say.
 */
internal fun validateS3ReadHeaders(
    headers: Headers,
    key: String,
    range: S3ByteRange?,
): Long? {
    val httpSizeHeader = headers[HttpHeaders.ContentLength]
    val httpSize = httpSizeHeader?.toLongOrNull()
    if (httpSizeHeader != null && (httpSize == null || httpSize < 0L)) {
        throw inconsistentS3Read(key, "GET returned invalid Content-Length")
    }
    if (range == null) {
        return httpSize
    }
    val rangeSize = validateContentRange(headers, key, range)
    if (httpSize != null && httpSize != rangeSize) {
        throw inconsistentS3Read(key, "GET Content-Length $httpSize did not match Content-Range length $rangeSize")
    }
    return rangeSize
}

/**
 * Fails when the HTTP engine decoded a stored `Content-Encoding` and so
 * changed the object bytes. Engines such as Darwin decode regardless of the
 * `Accept-Encoding` request header, and Ktor records what they removed.
 */
internal fun validateS3BodyEncoding(
    attributes: Attributes,
    key: String,
) {
    val decoded = attributes.getOrNull(DECODED_CONTENT_ENCODINGS)
        .orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("identity", ignoreCase = true) }
    if (decoded.isNotEmpty()) {
        throw S3Exception.Protocol(
            operation = S3Operation.Get,
            key = key,
            message = "HTTP engine decoded the ${decoded.joinToString()} content encoding of the object",
        )
    }
}

/** Checks that a partial response holds the requested range up to EOF and returns its length. */
@Suppress("ThrowsCount")
private fun validateContentRange(
    headers: Headers,
    key: String,
    range: S3ByteRange,
): Long {
    val contentRange = headers[HttpHeaders.ContentRange]
        ?.let(::parseContentRangeOrNull)
        ?: throw inconsistentS3Read(key, "partial GET returned an invalid or missing Content-Range")
    if (contentRange.start != range.offset) {
        throw inconsistentS3Read(key, "partial GET started at ${contentRange.start} instead of ${range.offset}")
    }
    val requestedEnd = range.endInclusiveOrNull()
    val resourceSize = contentRange.resourceSize
    val expectedRangeEnd = if (resourceSize != null) {
        // A satisfiable S3 range ends at EOF when its requested end is larger.
        minOf(requestedEnd ?: Long.MAX_VALUE, resourceSize - 1L)
    } else {
        requestedEnd
    }
    if (expectedRangeEnd != null && contentRange.endInclusive != expectedRangeEnd) {
        throw inconsistentS3Read(key, "partial GET did not return the complete requested byte range")
    }
    return contentRange.endInclusive - contentRange.start + 1L
}

/** Fails the read when the body is shorter or longer than [expectedSize]. */
internal fun validatingS3BodySource(
    upstream: Source,
    key: String,
    expectedSize: Long?,
): Source {
    if (expectedSize == null) {
        return upstream
    }
    return object : RawSource {
        private val scratch = Buffer()
        private var delivered = 0L

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val remaining = expectedSize - delivered
            val read = if (byteCount == 0L) {
                0L
            } else {
                val limit = minOf(remaining, READ_VALIDATION_CHUNK_SIZE) + 1L
                upstream.readAtMostTo(scratch, minOf(byteCount, READ_VALIDATION_CHUNK_SIZE, limit))
            }
            when {
                read == -1L && remaining != 0L ->
                    throw inconsistentS3Read(key, "GET ended after $delivered bytes; expected $expectedSize")
                read > remaining ->
                    throw inconsistentS3Read(key, "GET returned more than the expected $expectedSize bytes")
                read > 0L -> {
                    sink.write(scratch, read)
                    delivered += read
                }
            }
            return read
        }

        override fun close() {
            upstream.close()
        }
    }.buffered()
}

/** Ends the read after the first [limit] bytes of [upstream]. */
internal fun limitedS3BodySource(
    upstream: Source,
    limit: Long,
): Source = object : RawSource {
    private var remaining = limit

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (remaining == 0L) {
            return -1L
        }
        val read = upstream.readAtMostTo(sink, minOf(byteCount, remaining))
        if (read > 0L) {
            remaining -= read
        }
        return read
    }

    override fun close() {
        upstream.close()
    }
}.buffered()

internal fun S3ByteRange.toHttpRangeHeader(): String {
    val end = endInclusiveOrNull()
    return if (end != null) "bytes=$offset-$end" else "bytes=$offset-"
}

private fun S3ByteRange.endInclusiveOrNull(): Long? = length?.let { length ->
    require(length - 1L <= Long.MAX_VALUE - offset) {
        "S3 read range must not overflow."
    }
    offset + length - 1L
}

private data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val resourceSize: Long?,
)

private fun parseContentRangeOrNull(
    value: String,
): ContentRange? {
    val match = CONTENT_RANGE_REGEX.matchEntire(value.trim())
    val start = match?.groupValues?.get(1)?.toLongOrNull()
    val endInclusive = match?.groupValues?.get(2)?.toLongOrNull()
    val resourceSizeValue = match?.groupValues?.get(3)
    val resourceSize = resourceSizeValue?.takeUnless { it == "*" }?.toLongOrNull()
    val valid = start != null && endInclusive != null && start >= 0L && endInclusive >= start &&
        endInclusive - start < Long.MAX_VALUE &&
        (resourceSizeValue == "*" || resourceSize != null && resourceSize > 0L && endInclusive < resourceSize)
    return if (valid) ContentRange(start, endInclusive, resourceSize) else null
}

private fun inconsistentS3Read(
    key: String,
    message: String,
) = S3Exception.Protocol(
    operation = S3Operation.Get,
    key = key,
    message = message,
    retryable = true,
)

private const val READ_VALIDATION_CHUNK_SIZE = 8_192L

/** The key of `dropCompressionHeaders` in Ktor's client utilities. */
private val DECODED_CONTENT_ENCODINGS = AttributeKey<MutableList<String>>("DecompressionListAttribute")

private val CONTENT_RANGE_REGEX = Regex(
    pattern = "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
    option = RegexOption.IGNORE_CASE,
)
