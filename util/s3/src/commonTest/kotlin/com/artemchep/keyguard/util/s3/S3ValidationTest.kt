package com.artemchep.keyguard.util.s3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3ValidationTest {
    @Test
    fun `strict bucket names`() {
        listOf("abc", "my-bucket", "my.bucket.1", "a".repeat(63)).forEach {
            assertTrue(isValidS3BucketName(it), it)
        }
        listOf(
            "ab", "a".repeat(64), "My-Bucket", "my_bucket", "-abc", "abc-", "a..b", "a.-b", "a-.b", "192.168.1.1",
        ).forEach { assertFalse(isValidS3BucketName(it), it) }
    }

    @Test
    fun `path-style bucket names accept legacy characters`() {
        assertTrue(isValidS3BucketName("My_Bucket", pathStyle = true))
        assertFalse(isValidS3BucketName("a/b", pathStyle = true))
        assertFalse(isValidS3BucketName("ab", pathStyle = true))
    }

    @Test
    fun `access key IDs`() {
        assertTrue(isValidS3AccessKeyId("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(isValidS3AccessKeyId("minio-user_1@example.com"))
        for (value in listOf("", "AK ID", "AK\tID", "AK\u0000ID", "AKÏD", "AK\u007FID")) {
            assertFalse(isValidS3AccessKeyId(value), value)
        }
    }

    @Test
    fun `endpoint urls`() {
        listOf("https://s3.amazonaws.com", "http://127.0.0.1:9000", "https://example.com/base", " https://example.com ")
            .forEach { assertTrue(isValidS3EndpointUrl(it), it) }
        listOf(
            "", "example.com", "ftp://example.com", "https://u:p@example.com", "https://example.com?x",
            "https://example.com#y",
        ).forEach { assertFalse(isValidS3EndpointUrl(it), it) }
    }

    @Test
    fun `object keys`() {
        listOf("a", "a/b.kdbx", "dir with space/ü+%.kdbx", "...", ".hidden", "/", "/a", "a/", "a//b")
            .forEach {
                assertTrue(isValidS3ObjectKey(it), it)
            }
        listOf("", ".", "a/./b", "a/..", "a".repeat(1025)).forEach {
            assertFalse(isValidS3ObjectKey(it), it)
        }
    }

    @Test
    fun `unpaired surrogates are rejected instead of becoming another key`() {
        for (key in listOf("\uD800", "\uDC00", "prefix/\uD800suffix", "\uD800\uD800")) {
            assertFalse(isValidS3ObjectKey(key))
        }
        assertTrue(isValidS3ObjectKey("?"))
        assertTrue(isValidS3ObjectKey("😀"))
    }

    @Test
    fun `endpoint dot segments and malformed Unicode are rejected`() {
        for (path in listOf("/a/../b", "/a/./b", "/%2e/", "/%2E%2e/", "/.%2e/", "/\uD800")) {
            assertFalse(isValidS3EndpointUrl("https://example.com$path"))
        }
        assertTrue(isValidS3EndpointUrl("https://example.com/a//b/"))
        assertTrue(isValidS3EndpointUrl("https://example.com/%252e/"))
    }

    @Test
    fun `canonical host validation rejects encoded URL delimiters`() {
        for (host in listOf("example.com%2f", "example.com%3f", "example.com%23", "[bad]")) {
            assertFalse(isValidS3EndpointUrl("https://$host"))
        }
    }
}
