package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3Exception
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3ReadValidationTest {
    @Test
    fun `range headers handle open ended and maximum representable offsets`() {
        assertEquals("bytes=0-", S3ByteRange(0).toHttpRangeHeader())
        assertEquals("bytes=2-4", S3ByteRange(2, 3).toHttpRangeHeader())
        assertEquals("bytes=${Long.MAX_VALUE}-${Long.MAX_VALUE}", S3ByteRange(Long.MAX_VALUE, 1).toHttpRangeHeader())
        assertFailsWith<IllegalArgumentException> { S3ByteRange(Long.MAX_VALUE, 2).toHttpRangeHeader() }
    }

    @Test
    fun `full reads allow unknown length and reject invalid lengths`() {
        assertNull(validateS3ReadHeaders(Headers.Empty, "key", null))
        for (size in listOf(0L, 1L, Long.MAX_VALUE)) {
            assertEquals(size, validateS3ReadHeaders(headersOf(HttpHeaders.ContentLength, "$size"), "key", null))
        }
        for (size in listOf("", "-1", "abc", "9223372036854775808")) {
            val error = assertFailsWith<S3Exception.Protocol>(size) {
                validateS3ReadHeaders(headersOf(HttpHeaders.ContentLength, size), "key", null)
            }
            assertTrue(error.retryable)
        }
    }

    @Test
    fun `partial reads require a consistent complete range`() {
        for (value in listOf("bytes 2-4/5", "bytes 2-4/*", " BYTES 2-4/5 ")) {
            assertEquals(
                3L,
                validateS3ReadHeaders(headersOf(HttpHeaders.ContentRange, value), "key", S3ByteRange(2, 3)),
            )
        }
        assertEquals(
            3L,
            validateS3ReadHeaders(headersOf(HttpHeaders.ContentRange, "bytes 2-4/5"), "key", S3ByteRange(2)),
        )
        for (value in listOf(
            "",
            "items 2-4/5",
            "bytes 4-2/5",
            "bytes 2-5/5",
            "bytes 2-4/0",
            "bytes 1-3/5",
            "bytes 2-3/5",
            "bytes 2-4/9223372036854775808",
        )) {
            assertFailsWith<S3Exception.Protocol>(value) {
                validateS3ReadHeaders(headersOf(HttpHeaders.ContentRange, value), "key", S3ByteRange(2, 3))
            }
        }
        assertFailsWith<S3Exception.Protocol> {
            validateS3ReadHeaders(
                headersOf(HttpHeaders.ContentRange to listOf("bytes 2-4/5"), HttpHeaders.ContentLength to listOf("2")),
                "key",
                S3ByteRange(2, 3),
            )
        }
    }

    @Test
    fun `bounded ranges extending beyond EOF accept only the complete remaining bytes`() {
        val headers = headersOf(
            HttpHeaders.ContentRange to listOf("bytes 2-4/5"),
            HttpHeaders.ContentLength to listOf("3"),
        )
        for (length in listOf(4L, 100L, Long.MAX_VALUE - 1L)) {
            assertEquals(3L, validateS3ReadHeaders(headers, "key", S3ByteRange(2, length)))
        }
        for (contentRange in listOf("bytes 2-3/5", "bytes 2-3/*", "bytes 1-4/5")) {
            assertFailsWith<S3Exception.Protocol>(contentRange) {
                validateS3ReadHeaders(headersOf(HttpHeaders.ContentRange, contentRange), "key", S3ByteRange(2, 100))
            }
        }
        assertFailsWith<S3Exception.Protocol> {
            validateS3ReadHeaders(
                headersOf(
                    HttpHeaders.ContentRange to listOf("bytes 2-4/5"),
                    HttpHeaders.ContentLength to listOf("100"),
                ),
                "key",
                S3ByteRange(2, 100),
            )
        }
    }

    @Test
    fun `content range length must not overflow`() {
        assertFailsWith<S3Exception.Protocol> {
            validateS3ReadHeaders(
                headersOf(HttpHeaders.ContentRange, "bytes 0-${Long.MAX_VALUE}/*"),
                "key",
                S3ByteRange(0),
            )
        }
    }

    @Test
    fun `maximum content length does not overflow the read limit`() {
        val upstream = Buffer().apply { write(byteArrayOf(42)) }
        validatingS3BodySource(upstream, "key", Long.MAX_VALUE).use { source ->
            assertEquals(42.toByte(), source.readByte())
            assertFailsWith<S3Exception.Protocol> { source.readByteArray() }
        }
    }

    @Test
    fun `zero reads do not validate EOF and exact reads finish cleanly`() {
        for (size in listOf(0, 1, 8192, 8193, 20000)) {
            val upstream = Buffer().apply { write(ByteArray(size) { 42 }) }
            validatingS3BodySource(upstream, "key", size.toLong()).use { source ->
                // kotlinx-io's buffered Source returns -1 even for a zero read at EOF.
                assertEquals(if (size == 0) -1L else 0L, source.readAtMostTo(Buffer(), 0))
                assertEquals(size, source.readByteArray().size)
                assertEquals(-1L, source.readAtMostTo(Buffer(), 1))
            }
        }
    }

    @Test
    fun `both short and excess bodies fail across chunk boundaries`() {
        for ((actual, expected) in listOf(0 to 1, 1 to 0, 8193 to 8192, 8192 to 8193)) {
            val upstream = Buffer().apply { write(ByteArray(actual)) }
            validatingS3BodySource(upstream, "key", expected.toLong()).use { source ->
                assertFailsWith<S3Exception.Protocol>("$actual/$expected") { source.readByteArray() }
            }
        }
    }
}
