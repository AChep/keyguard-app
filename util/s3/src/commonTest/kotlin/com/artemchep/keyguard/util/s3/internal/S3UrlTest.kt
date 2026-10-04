package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S3UrlTest {
    @Test
    fun `unreserved characters are kept`() {
        assertEquals("AZaz09-_.~", s3UriEncode("AZaz09-_.~", encodeSlash = true))
    }

    @Test
    fun `reserved and non-ASCII characters are percent-encoded in uppercase`() {
        assertEquals(
            "a%20b%2B%2A%21%27%28%29%24%23%3F%25%26%3D%C3%A9",
            s3UriEncode("a b+*!'()$#?%&=é", encodeSlash = true),
        )
    }

    @Test
    fun `slashes are kept in paths and encoded in query values`() {
        assertEquals("a/b", s3UriEncode("a/b", encodeSlash = false))
        assertEquals("a%2Fb", s3UriEncode("a/b", encodeSlash = true))
    }

    @Test
    fun `canonical query sorts by encoded name and keeps empty values`() {
        assertEquals(
            "a=&delimiter=%2F&list-type=2&prefix=x%20y",
            s3CanonicalQuery(
                listOf(
                    "prefix" to "x y",
                    "list-type" to "2",
                    "delimiter" to "/",
                    "a" to "",
                ),
            ),
        )
    }

    @Test
    fun `path-style addressing puts the bucket in the path`() {
        val addressing = S3Addressing(config(endpoint = "http://127.0.0.1:9000", pathStyle = true))

        val target = addressing.objectTarget("dir/file name.kdbx")

        assertEquals("http://127.0.0.1:9000/bucket/dir/file%20name.kdbx", target.url)
        assertEquals("127.0.0.1:9000", target.host)
        assertEquals("/bucket/dir/file%20name.kdbx", target.canonicalUri)
    }

    @Test
    fun `virtual-hosted addressing puts the bucket in the host`() {
        val addressing = S3Addressing(config(endpoint = "https://s3.eu-west-1.amazonaws.com", pathStyle = false))

        val target = addressing.bucketTarget(listOf("list-type" to "2"))

        assertEquals("https://bucket.s3.eu-west-1.amazonaws.com/?list-type=2", target.url)
        assertEquals("bucket.s3.eu-west-1.amazonaws.com", target.host)
        assertEquals("/", target.canonicalUri)
        assertEquals("list-type=2", target.canonicalQuery)
    }

    @Test
    fun `virtual-hosted addressing falls back to path-style when the bucket cannot be a subdomain`() {
        val endpoints = listOf(
            "http://192.168.1.10:9000" to "bucket",
            "http://[::1]:9000" to "bucket",
            // A wildcard certificate covers one label only.
            "https://s3.eu-west-1.amazonaws.com" to "my.bucket",
        )
        for ((endpoint, bucket) in endpoints) {
            val target = S3Addressing(config(endpoint = endpoint, bucket = bucket, pathStyle = false))
                .objectTarget("key")
            assertEquals(endpoint.removePrefix("http://").removePrefix("https://"), target.host, endpoint)
            assertEquals("/$bucket/key", target.canonicalUri, endpoint)
        }
        val plain = S3Addressing(config(endpoint = "http://minio.lan:9000", bucket = "my.bucket", pathStyle = false))
            .objectTarget("key")
        assertEquals("my.bucket.minio.lan:9000", plain.host)
        assertEquals("/key", plain.canonicalUri)
    }

    @Test
    fun `the soap object uses path-style addressing on AWS without changing bucket requests`() {
        for (endpoint in listOf(
            "https://s3.amazonaws.com",
            "https://s3-external-1.amazonaws.com",
            "http://s3.us-east-1.amazonaws.com",
            "https://s3.eu-west-1.amazonaws.com",
            "https://s3-us-west-2.amazonaws.com",
            "https://s3.cn-north-1.amazonaws.com.cn",
        )) {
            val addressing = S3Addressing(config(endpoint = endpoint, pathStyle = false))
            val endpointHost = endpoint.substringAfter("://")

            assertEquals(
                S3RequestTarget("$endpoint/bucket/soap", endpointHost, "/bucket/soap", ""),
                addressing.objectTarget("soap"),
                endpoint,
            )
            assertEquals(
                "bucket.$endpointHost",
                addressing.bucketTarget(listOf("list-type" to "2")).host,
                endpoint,
            )
            assertEquals("/", addressing.bucketTarget().canonicalUri, endpoint)
            assertEquals("bucket.$endpointHost", addressing.objectTarget("key").host, endpoint)
        }
    }

    @Test
    fun `the soap object uses the default AWS endpoint for its partition`() {
        for ((region, endpointHost) in listOf(
            "us-east-1" to "s3.us-east-1.amazonaws.com",
            "cn-north-1" to "s3.cn-north-1.amazonaws.com.cn",
            "eusc-de-east-1" to "s3.eusc-de-east-1.amazonaws.eu",
        )) {
            val target = S3Addressing(config(endpoint = null, region = region, pathStyle = false))
                .objectTarget("soap")
            assertEquals(
                S3RequestTarget("https://$endpointHost/bucket/soap", endpointHost, "/bucket/soap", ""),
                target,
                region,
            )
        }
    }

    @Test
    fun `the soap fallback preserves the canonical endpoint port and base path`() {
        val target = S3Addressing(config(
            endpoint = "https://S3.EU-WEST-1.AMAZONAWS.COM:9443/base%20path/",
            pathStyle = false,
        )).objectTarget("soap")

        assertEquals(
            S3RequestTarget(
                "https://s3.eu-west-1.amazonaws.com:9443/base%20path/bucket/soap",
                "s3.eu-west-1.amazonaws.com:9443",
                "/base%20path/bucket/soap",
                "",
            ),
            target,
        )
    }

    @Test
    fun `only the exact soap object key uses the AWS fallback`() {
        val addressing = S3Addressing(config(endpoint = "https://s3.amazonaws.com", pathStyle = false))

        for (key in listOf("Soap", "SOAP", "dir/soap", "soap/", "/soap", "soapy")) {
            assertEquals(
                S3RequestTarget("https://bucket.s3.amazonaws.com/$key", "bucket.s3.amazonaws.com", "/$key", ""),
                addressing.objectTarget(key),
                key,
            )
        }
    }

    @Test
    fun `the soap object keeps explicit path-style and custom virtual-hosted addressing`() {
        for (endpoint in listOf("https://s3.amazonaws.com", "https://minio.lan:9000")) {
            val target = S3Addressing(config(endpoint = endpoint, pathStyle = true)).objectTarget("soap")
            assertEquals("$endpoint/bucket/soap", target.url)
            assertEquals(endpoint.substringAfter("://"), target.host)
            assertEquals("/bucket/soap", target.canonicalUri)
        }
        for (endpointHost in listOf(
            "minio.lan:9000",
            "s3.example.com",
            "s3.amazonaws.com.example.com",
            "s3-accelerate.amazonaws.com",
            "s3-accesspoint.us-east-1.amazonaws.com",
        )) {
            val target = S3Addressing(config(endpoint = "https://$endpointHost", pathStyle = false))
                .objectTarget("soap")
            assertEquals("https://bucket.$endpointHost/soap", target.url, endpointHost)
            assertEquals("bucket.$endpointHost", target.host, endpointHost)
            assertEquals("/soap", target.canonicalUri, endpointHost)
        }
    }

    @Test
    fun `the default port is omitted from the host`() {
        val addressing = S3Addressing(config(endpoint = "https://example.com:443", pathStyle = true))

        assertEquals("example.com", addressing.bucketTarget().host)
    }

    @Test
    fun `an endpoint base path prefixes every request`() {
        val addressing = S3Addressing(config(endpoint = "https://example.com/storage/", pathStyle = true))

        assertEquals("/storage/bucket", addressing.bucketTarget().canonicalUri)
        assertEquals("/storage/bucket/a.kdbx", addressing.objectTarget("a.kdbx").canonicalUri)
    }

    @Test
    fun `a blank endpoint resolves to Amazon S3 in the region`() {
        val addressing = S3Addressing(config(endpoint = " ", region = "eu-central-1", pathStyle = false))

        assertEquals("eu-central-1", addressing.region)
        assertEquals("bucket.s3.eu-central-1.amazonaws.com", addressing.bucketTarget().host)
    }

    @Test
    fun `a blank region defaults to us-east-1`() {
        assertEquals("us-east-1", S3Addressing(config(region = null)).region)
    }

    @Test
    fun `virtual-hosted addressing rejects a bucket that is not a DNS name`() {
        assertFailsWith<IllegalArgumentException> {
            S3Addressing(config(bucket = "My_Bucket", pathStyle = false))
        }
        // Path-style addressing accepts legacy names.
        S3Addressing(config(bucket = "My_Bucket", pathStyle = true))
    }

    @Test
    fun `empty keys and dot segments are rejected before engines can normalize them`() {
        val addressing = S3Addressing(config())

        listOf("", ".", "./a", "a/./b", "a/../b", "..").forEach { key ->
            assertFailsWith<IllegalArgumentException>(key) {
                addressing.objectTarget(key)
            }
        }
    }

    @Test
    fun `leading trailing and repeated slashes remain part of object keys`() {
        for (pathStyle in listOf(true, false)) {
            val addressing = S3Addressing(config(pathStyle = pathStyle))
            val prefix = if (pathStyle) "/bucket/" else "/"
            for (key in listOf("/", "/a", "a/", "a//b", "//a//")) {
                val target = addressing.objectTarget(key)
                assertEquals(prefix + key, target.canonicalUri)
                assertEquals("https://${target.host}${target.canonicalUri}", target.url)
            }
        }
    }

    @Test
    fun `malformed Unicode is rejected in keys and query values`() {
        val addressing = S3Addressing(config())
        for (invalid in listOf("\uD800", "\uDC00")) {
            assertFailsWith<IllegalArgumentException> { addressing.objectTarget(invalid) }
            assertFailsWith<IllegalArgumentException> { addressing.bucketTarget(listOf("prefix" to invalid)) }
        }
        assertEquals("/bucket/%3F", addressing.objectTarget("?").canonicalUri)
        assertEquals("/bucket/%F0%9F%98%80", addressing.objectTarget("😀").canonicalUri)
    }

    @Test
    fun `endpoint base paths preserve interior and additional trailing separators`() {
        for ((path, expectedPrefix) in listOf(
            "/base//path/" to "/base//path/",
            "/base//" to "/base//",
            "//base/" to "//base/",
            "/base" to "/base/",
        )) {
            for (pathStyle in listOf(true, false)) {
                val addressing = S3Addressing(config(endpoint = "https://example.com$path", pathStyle = pathStyle))
                val objectPrefix = expectedPrefix + if (pathStyle) "bucket/" else ""
                assertEquals(objectPrefix + "key", addressing.objectTarget("key").canonicalUri)
            }
        }
    }

    @Test
    fun `endpoint dot segments are rejected before signing`() {
        for (path in listOf("/base/../path/", "/base/./path/", "/base/%2e%2e/path/", "/%2E/")) {
            assertFailsWith<IllegalArgumentException> {
                S3Addressing(config(endpoint = "https://example.com$path"))
            }
        }
    }

    @Test
    fun `canonical endpoint hosts are used in both signatures and URLs`() {
        for ((endpoint, expectedHost) in listOf(
            "https://EXAMPLE.COM" to "example.com",
            "https://bücher.example" to "xn--bcher-kva.example",
            "https://faß.example" to "xn--fa-hia.example",
            "http://[0:0:0:0:0:0:0:1]:9000" to "[::1]:9000",
            "http://[2001:0DB8:0:0:0:0:0:1]:9000" to "[2001:db8::1]:9000",
        )) {
            val target = S3Addressing(config(endpoint = endpoint)).objectTarget("key")
            assertEquals(expectedHost, target.host)
            assertEquals(endpoint.substringBefore(":") + "://" + expectedHost + "/bucket/key", target.url)
        }
    }

    @Test
    fun `virtual hosted endpoints canonicalize the endpoint domain`() {
        val target = S3Addressing(config(endpoint = "https://BÜCHER.example:443", pathStyle = false))
            .objectTarget("key")
        assertEquals("bucket.xn--bcher-kva.example", target.host)
        assertEquals("https://bucket.xn--bcher-kva.example/key", target.url)
    }

    @Test
    fun `China regions use the China partition when the endpoint is omitted`() {
        for (region in listOf("cn-north-1", "cn-northwest-1")) {
            val target = S3Addressing(config(endpoint = null, region = region)).objectTarget("key")
            assertEquals("https://s3.$region.amazonaws.com.cn/bucket/key", target.url)
        }
    }

    @Test
    fun `endpoints with credentials or a query or a fragment are rejected`() {
        listOf(
            "ftp://example.com",
            "example.com",
            "https://user:pass@example.com",
            "https://example.com/?a=b",
            "https://example.com/#x",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                S3Addressing(config(endpoint = endpoint))
            }
        }
    }

    @Test
    fun `canonical query sorts duplicate encoded names and values without losing duplicates`() {
        assertEquals(
            "%C3%A9=x&a=&a=%20&a=%2B&a=%2B&a=%C3%A9&a=z",
            s3CanonicalQuery(listOf(
                "a" to "z", "a" to "+", "é" to "x", "a" to "é",
                "a" to "", "a" to " ", "a" to "+",
            )),
        )
    }

    @Test
    fun `literal percent encodings and supplementary Unicode remain distinct path data`() {
        val target = S3Addressing(config()).objectTarget("%2F/%25/😀")
        assertEquals("/bucket/%252F/%2525/%F0%9F%98%80", target.canonicalUri)
        assertEquals("https://example.com" + target.canonicalUri, target.url)
    }

    @Test
    fun `encoded base paths and IPv6 ports agree with the signed host and URI`() {
        val target = S3Addressing(config(endpoint = "http://[::1]:9000/base%20path/%252F/"))
            .objectTarget("a+b")
        assertEquals("[::1]:9000", target.host)
        assertEquals("/base%20path/%252F/bucket/a%2Bb", target.canonicalUri)
        assertEquals("http://[::1]:9000" + target.canonicalUri, target.url)
    }

    private fun config(
        endpoint: String? = "https://example.com",
        region: String? = "us-east-1",
        bucket: String = "bucket",
        pathStyle: Boolean = true,
    ) = S3ClientConfig(
        endpoint = endpoint,
        region = region,
        bucket = bucket,
        credentials = S3Credentials("AKID", "secret"),
        pathStyle = pathStyle,
    )
}
