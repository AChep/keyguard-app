package com.artemchep.keyguard.util.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class S3ModelsTest {
    @Test
    fun `credentials validate identity and redact secrets in nested descriptions`() {
        // Header values cannot carry control or non-ASCII characters.
        for (access in listOf("", " ", "\t\n", "AK\u0001ID", "AK ID", "AKÏD")) {
            assertFailsWith<IllegalArgumentException> { S3Credentials(access, "secret") }
        }
        assertFailsWith<IllegalArgumentException> { S3Credentials("access", "") }
        val credentials = S3Credentials("access", "unique-secret")
        assertEquals(credentials, S3Credentials("access", "unique-secret"))
        assertEquals(credentials.hashCode(), S3Credentials("access", "unique-secret").hashCode())
        assertNotEquals(credentials, S3Credentials("other", "unique-secret"))
        assertNotEquals(credentials, S3Credentials("access", "other"))
        assertFalse("unique-secret" in credentials.toString())
        assertFalse("unique-secret" in testS3Config().copy(credentials = credentials).toString())
        assertEquals(" ", S3Credentials("access", " ").secretAccessKey)
    }

    @Test
    fun `AWS endpoints use the domain of the region partition`() {
        val endpoints = mapOf(
            "us-east-1" to "https://s3.us-east-1.amazonaws.com",
            "us-gov-west-1" to "https://s3.us-gov-west-1.amazonaws.com",
            "cn-north-1" to "https://s3.cn-north-1.amazonaws.com.cn",
            "eusc-de-east-1" to "https://s3.eusc-de-east-1.amazonaws.eu",
            "us-iso-east-1" to "https://s3.us-iso-east-1.c2s.ic.gov",
            "us-isob-east-1" to "https://s3.us-isob-east-1.sc2s.sgov.gov",
            "us-isof-south-1" to "https://s3.us-isof-south-1.csp.hci.ic.gov",
            "eu-isoe-west-1" to "https://s3.eu-isoe-west-1.cloud.adc-e.uk",
        )
        for ((region, endpoint) in endpoints) {
            assertEquals(endpoint, S3Endpoints.aws(region))
        }
    }

    @Test
    fun `range and write precondition constructors reject invalid inputs`() {
        assertFailsWith<IllegalArgumentException> { S3ByteRange(-1) }
        for (length in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { S3ByteRange(0, length) }
        }
        for (etag in listOf("", " ", "\n")) {
            assertFailsWith<IllegalArgumentException> { S3WritePrecondition.IfMatch(etag) }
        }
        assertEquals(Long.MAX_VALUE, S3ByteRange(Long.MAX_VALUE).offset)
        assertEquals("\"opaque-2\"", S3WritePrecondition.IfMatch("\"opaque-2\"").etag)
    }

    @Test
    fun `only transient failures and torn reads are retryable`() {
        val recoverable = listOf(
            S3Exception.Transient(S3Operation.Get, "key"),
            S3Exception.Protocol(S3Operation.Get, "key", "torn response", retryable = true),
        )
        recoverable.forEach { assertTrue(it.retryable, it.toString()) }
        val permanent = listOf(
            S3Exception.Protocol(S3Operation.Get, "key", "server rejected the request", statusCode = 400),
            S3Exception.NotFound(S3Operation.Get, "key"),
            S3Exception.BucketNotFound(S3Operation.Get),
            S3Exception.AlreadyExists(S3Operation.Put, "key"),
            S3Exception.PreconditionFailed(S3Operation.Put, "key"),
            S3Exception.InvalidRange(S3Operation.Get, "key"),
            S3Exception.AuthenticationFailed(S3Operation.Get),
            S3Exception.PermissionDenied(S3Operation.Get, "key"),
            S3Exception.WrongRegion(S3Operation.Get, "eu-west-1"),
            S3Exception.ClockSkew(S3Operation.Get, null),
            S3Exception.InsufficientStorage(S3Operation.Put, "key"),
        )
        permanent.forEach { assertFalse(it.retryable, it.toString()) }
    }

    @Test
    fun `key limits count UTF8 bytes including supplementary characters`() {
        for (key in listOf("a".repeat(1024), "é".repeat(512), "😀".repeat(256))) {
            assertTrue(isValidS3ObjectKey(key))
            assertFalse(isValidS3ObjectKey(key + "a"))
        }
        assertTrue(isValidS3ObjectKey("a/%2F/%2E%2E"))
        assertFalse(isValidS3ObjectKey("a/../b"))
    }

    @Test
    fun `legacy bucket limits and endpoint boundaries`() {
        assertTrue(isValidS3BucketName("A_" + "a".repeat(253), pathStyle = true))
        assertFalse(isValidS3BucketName("a".repeat(256), pathStyle = true))
        assertTrue(isValidS3EndpointUrl("HTTP://[::1]:9000/base"))
        for (endpoint in listOf(
            "//example.com",
            "https://user@example.com",
            "https://example.com?",
            "https://example.com#",
        )) {
            assertFalse(isValidS3EndpointUrl(endpoint), endpoint)
        }
    }
}
