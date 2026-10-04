package com.artemchep.keyguard.util.s3

import com.artemchep.keyguard.util.foundation.crypto.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/**
 * Vectors from the AWS "Signature Calculations for the Authorization Header:
 * Transferring Payload in a Single Chunk" examples.
 */
class S3SigV4SignerTest {
    private val signer = S3SigV4Signer(
        credentials = S3Credentials(
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        ),
        region = "us-east-1",
    )
    private val now = Instant.parse("2013-05-24T00:00:00Z")

    @Test
    fun `GET object with range`() {
        val headers = signer.sign(
            method = "GET",
            canonicalUri = "/test.txt",
            canonicalQuery = "",
            headers = mapOf(
                "Host" to "examplebucket.s3.amazonaws.com",
                "Range" to "bytes=0-9",
            ),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            headers.getValue("Authorization"),
        )
        assertEquals("20130524T000000Z", headers.getValue("x-amz-date"))
        assertEquals(S3SigV4Signer.EMPTY_PAYLOAD_SHA256, headers.getValue("x-amz-content-sha256"))
    }

    @Test
    fun `PUT object with an encoded key and extra headers`() {
        val payload = "Welcome to Amazon S3.".encodeToByteArray()
        val payloadHash = sha256(payload).toHexString()
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payloadHash)

        val headers = signer.sign(
            method = "PUT",
            canonicalUri = "/test%24file.text",
            canonicalQuery = "",
            headers = mapOf(
                "Host" to "examplebucket.s3.amazonaws.com",
                "Date" to "Fri, 24 May 2013 00:00:00 GMT",
                "x-amz-storage-class" to "REDUCED_REDUNDANCY",
            ),
            payloadSha256Hex = payloadHash,
            now = now,
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, " +
                "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            headers.getValue("Authorization"),
        )
    }

    @Test
    fun `GET bucket lifecycle with an empty query value`() {
        val headers = signer.sign(
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "lifecycle=",
            headers = mapOf("Host" to "examplebucket.s3.amazonaws.com"),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
            headers.getValue("Authorization"),
        )
    }

    @Test
    fun `GET bucket listing with query parameters`() {
        val headers = signer.sign(
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "max-keys=2&prefix=J",
            headers = mapOf("Host" to "examplebucket.s3.amazonaws.com"),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            headers.getValue("Authorization"),
        )
    }

    @Test
    fun `signing key derivation matches the generic SigV4 example`() {
        val signer = S3SigV4Signer(
            credentials = S3Credentials(
                accessKeyId = "AKIDEXAMPLE",
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            ),
            region = "us-east-1",
            service = "iam",
        )

        assertEquals(
            "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9",
            signer.signingKey("20150830").toHexString(),
        )
    }

    @Test
    fun `header values are trimmed and inner whitespace is collapsed`() {
        val plain = signer.sign(
            method = "GET",
            canonicalUri = "/test.txt",
            canonicalQuery = "",
            headers = mapOf("Host" to "examplebucket.s3.amazonaws.com", "Range" to "bytes=0-9"),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        )
        val padded = signer.sign(
            method = "GET",
            canonicalUri = "/test.txt",
            canonicalQuery = "",
            headers = mapOf("host" to " examplebucket.s3.amazonaws.com ", "range" to "  bytes=0-9"),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        )

        assertEquals(plain.getValue("Authorization"), padded.getValue("Authorization"))
    }

    @Test
    fun `fractional seconds are dropped from the request date`() {
        val headers = signer.sign(
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "",
            headers = mapOf("Host" to "examplebucket.s3.amazonaws.com"),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = Instant.parse("2024-02-29T23:59:58.999Z"),
        )

        assertEquals("20240229T235958Z", headers.getValue("x-amz-date"))
    }

    @Test
    fun `the host header must be signed`() {
        assertFailsWith<IllegalArgumentException> {
            signer.sign(
                method = "GET",
                canonicalUri = "/",
                canonicalQuery = "",
                headers = emptyMap(),
                payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
                now = now,
            )
        }
    }

    @Test
    fun `header order case and embedded whitespace do not alter signatures`() {
        fun sign(headers: Map<String, String>) = signer.sign(
            method = "PUT",
            canonicalUri = "/key",
            canonicalQuery = "",
            headers = headers,
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        ).getValue("Authorization")
        assertEquals(
            sign(linkedMapOf("host" to "example.com", "x-amz-meta-note" to "a b c")),
            sign(linkedMapOf("X-Amz-Meta-Note" to " a\t b  c ", "HOST" to " example.com ")),
        )
    }

    @Test
    fun `midnight updates both the request date and credential scope`() {
        for ((instant, date) in listOf(
            "2024-02-29T23:59:59.999Z" to "20240229T235959Z",
            "2024-03-01T00:00:00Z" to "20240301T000000Z",
            "2025-01-01T00:00:00Z" to "20250101T000000Z",
        )) {
            val headers = signer.sign(
                method = "GET",
                canonicalUri = "/key",
                canonicalQuery = "",
                headers = mapOf("host" to "example.com"),
                payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
                now = Instant.parse(instant),
            )
            assertEquals(date, headers.getValue("x-amz-date"))
            assertEquals(date.take(8), headers.getValue("Authorization").substringAfter('/').substringBefore('/'))
        }
    }

    @Test
    fun `credentials do not leak the secret through toString`() {
        val credentials = S3Credentials("AKID", "top-secret")

        assertEquals(false, "top-secret" in credentials.toString())
    }

    @Test
    fun `repeated headers combine all values in their original order regardless of name case`() {
        fun sign(headers: Map<String, String>) = signer.sign(
            method = "PUT",
            canonicalUri = "/key",
            canonicalQuery = "",
            headers = headers,
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        ).getValue("Authorization")

        assertEquals(
            sign(mapOf("host" to "example.com", "x-amz-meta-tag" to "first value,second value")),
            sign(linkedMapOf(
                "host" to "example.com",
                "X-Amz-Meta-Tag" to " first\tvalue ",
                "x-amz-meta-tag" to "second  value",
            )),
        )
    }

    @Test
    fun `generated signing headers replace supplied values regardless of case`() {
        fun sign(headers: Map<String, String>) = signer.sign(
            method = "GET",
            canonicalUri = "/key",
            canonicalQuery = "",
            headers = headers,
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = now,
        )

        assertEquals(
            sign(mapOf("host" to "example.com")),
            sign(mapOf(
                "host" to "example.com",
                "X-Amz-Date" to "20000101T000000Z",
                "X-Amz-Content-Sha256" to S3SigV4Signer.UNSIGNED_PAYLOAD,
            )),
        )
    }
}
