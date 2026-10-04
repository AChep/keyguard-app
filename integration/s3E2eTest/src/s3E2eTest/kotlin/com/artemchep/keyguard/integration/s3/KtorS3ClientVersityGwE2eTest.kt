package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class KtorS3ClientVersityGwE2eTest {
    @Test
    fun `put get and head round trip a large object`() = runTest(timeout = 120.seconds) {
        val payload = Random(42).nextBytes(20 * 1024 * 1024)
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    val key = "large/$name.bin"
                    val written = client.putObject(key, payload)
                    assertNotNull(written.etag, name)

                    val head = assertNotNull(client.headObject(key), name)
                    assertEquals(payload.size.toLong(), head.size, name)
                    assertEquals(written.etag, head.etag, name)
                    assertNotNull(head.lastModified, name)

                    val read = client.getObject(key).use { it.readByteArray() }
                    assertContentEquals(payload, read, name)
                }
            }
        }
    }

    @Test
    fun `keys with reserved and non-ASCII characters round trip and list decoded`() = runTest {
        val keys = listOf(
            "dir with space/file name.kdbx",
            "plus+percent%hash#question?.kdbx",
            "tilde~star*paren(1)'quote'.kdbx",
            "ünïcødé/日本語.kdbx",
            "amp&eq=semi;comma,.kdbx",
            "emoji😀/literal%2F+%2525.kdbx",
        )
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    keys.forEach { key ->
                        val full = "$name/$key"
                        client.putObject(full, full.encodeToByteArray())
                        val read = client.getObject(full).use { it.readByteArray() }
                        assertEquals(full, read.decodeToString())
                    }
                    val listed = client.listObjects(prefix = "$name/").objects.map { it.key }
                    assertEquals(keys.map { "$name/$it" }.sorted(), listed.sorted(), name)
                }
            }
        }
    }

    @Test
    fun `ranged reads`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (_, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    client.putObject("range.bin", "0123456789".encodeToByteArray())

                    val middle = client.getObject("range.bin", S3ByteRange(offset = 2, length = 3))
                        .use { it.readByteArray() }
                    val tail = client.getObject("range.bin", S3ByteRange(offset = 7)).use { it.readByteArray() }

                    assertEquals("234", middle.decodeToString())
                    assertEquals("789", tail.decodeToString())
                    assertEquals(
                        "789",
                        client.getObject("range.bin", S3ByteRange(7, 100)).use { it.readByteArray().decodeToString() },
                    )
                    assertEquals(
                        "9",
                        client.getObject("range.bin", S3ByteRange(9, 1)).use { it.readByteArray().decodeToString() },
                    )
                    assertEquals(10, client.getObject("range.bin", S3ByteRange(0, 10)).use { it.readByteArray().size })
                    assertFailsWith<S3Exception.InvalidRange> {
                        client.getObject("range.bin", S3ByteRange(offset = 100))
                    }
                }
            }
        }
    }

    @Test
    fun `list with delimiter and pagination`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (_, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    listOf("p/a.bin", "p/b.bin", "p/c.bin", "p/d.bin", "p/e.bin", "p/sub/x.bin", "p/sub2/y.bin")
                        .forEach { client.putObject(it, byteArrayOf(1)) }

                    val empty = client.listObjects(prefix = "p/", maxKeys = 0)
                    assertTrue(empty.objects.isEmpty())
                    assertNull(empty.nextContinuationToken)

                    val rolledUp = client.listObjects(prefix = "p/", delimiter = "/")
                    assertEquals(
                        listOf("p/a.bin", "p/b.bin", "p/c.bin", "p/d.bin", "p/e.bin"),
                        rolledUp.objects.map { it.key },
                    )
                    assertEquals(listOf("p/sub/", "p/sub2/"), rolledUp.commonPrefixes)

                    val keys = mutableListOf<String>()
                    var token: String? = null
                    var pages = 0
                    do {
                        val page = client.listObjects(prefix = "p/", continuationToken = token, maxKeys = 2)
                        keys += page.objects.map { it.key }
                        token = page.nextContinuationToken
                        pages += 1
                    } while (token != null)
                    assertEquals(
                        listOf("p/a.bin", "p/b.bin", "p/c.bin", "p/d.bin", "p/e.bin", "p/sub/x.bin", "p/sub2/y.bin"),
                        keys,
                    )
                    assertEquals(4, pages)
                }
            }
        }
    }

    @Test
    fun `conditional writes are enforced`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    val key = "$name/db.kdbx"
                    val first = client.putObject(key, byteArrayOf(1), S3WritePrecondition.IfNoneMatch)
                    assertFailsWith<S3Exception.AlreadyExists>(name) {
                        client.putObject(key, byteArrayOf(2), S3WritePrecondition.IfNoneMatch)
                    }

                    val etag = assertNotNull(first.etag)
                    val second = client.putObject(key, byteArrayOf(3), S3WritePrecondition.IfMatch(etag))
                    assertNotEquals(first.etag, second.etag, name)
                    assertFailsWith<S3Exception.PreconditionFailed>(name) {
                        client.putObject(key, byteArrayOf(5), S3WritePrecondition.IfMatch("W/${second.etag}"))
                    }
                    assertFailsWith<S3Exception.PreconditionFailed>(name) {
                        client.putObject(key, byteArrayOf(4), S3WritePrecondition.IfMatch(etag))
                    }

                    val read = client.getObject(key).use { it.readByteArray() }
                    assertContentEquals(byteArrayOf(3), read, name)

                    // Another device deleted the object that this write expects.
                    client.deleteObject(key)
                    assertFailsWith<S3Exception.PreconditionFailed>(name) {
                        client.putObject(key, byteArrayOf(6), S3WritePrecondition.IfMatch(assertNotNull(second.etag)))
                    }
                }
            }
        }
    }

    @Test
    fun `keys that collide with files or directories fail permanently`() = runTest {
        withS3Gateway { server ->
            server.client().useS3Client { client ->
                client.putObject("file", byteArrayOf(1))
                client.putObject("dir/child", byteArrayOf(2))

                // The POSIX backend stores keys as paths, and answers with a 409.
                val parentIsFile = assertFailsWith<S3Exception.Protocol> {
                    client.putObject("file/child", byteArrayOf(3))
                }
                assertEquals(409, parentIsFile.statusCode)
                assertFalse(parentIsFile.retryable)
                val isDirectory = assertFailsWith<S3Exception.Protocol> {
                    client.putObject("dir", byteArrayOf(4))
                }
                assertFalse(isDirectory.retryable)
            }
        }
    }

    @Test
    fun `concurrent creates cannot overwrite the winning upload`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    val key = "$name/concurrent.bin"
                    val results = coroutineScope {
                        listOf("first", "second").map { value ->
                            async {
                                value to runCatching {
                                    client.putObject(key, value.encodeToByteArray(), S3WritePrecondition.IfNoneMatch)
                                }
                            }
                        }.awaitAll()
                    }
                    val winner = results.single { it.second.isSuccess }.first
                    val failure = results.single { it.second.isFailure }.second.exceptionOrNull()
                    assertTrue(failure is S3Exception.AlreadyExists || failure is S3Exception.Transient, name)
                    assertEquals(winner, client.getObject(key).use { it.readByteArray().decodeToString() })
                }
            }
        }
    }

    @Test
    fun `delete existing and missing objects`() = runTest {
        withS3Gateway { server ->
            server.client().useS3Client { client ->
                client.putObject("gone.bin", byteArrayOf(1))

                client.deleteObject("gone.bin")
                client.deleteObject("gone.bin")

                assertNull(client.headObject("gone.bin"))
                assertFailsWith<S3Exception.NotFound> { client.getObject("gone.bin") }
            }
        }
    }

    @Test
    fun `authentication and bucket errors`() = runTest {
        withS3Gateway { server ->
            server.client(secretAccessKey = "wrong").useS3Client { client ->
                assertFailsWith<S3Exception.AuthenticationFailed> { client.listObjects() }
            }
            server.client().useS3Client { client -> client.putObject("protected.bin", byteArrayOf(7)) }
            server.client(accessKeyId = "unknown").useS3Client { client ->
                assertFailsWith<S3Exception.AuthenticationFailed> { client.listObjects() }
                assertFailsWith<S3Exception.AuthenticationFailed> { client.deleteObject("protected.bin") }
            }
            server.client().useS3Client { client ->
                assertContentEquals(byteArrayOf(7), client.getObject("protected.bin").use { it.readByteArray() })
            }
            server.client(bucket = "missing-bucket").useS3Client { client ->
                assertFailsWith<S3Exception.BucketNotFound> { client.listObjects() }
                assertFailsWith<S3Exception.BucketNotFound> { client.getObject("a.bin") }
            }
        }
        val unreachable = VersityGwServer.unusedEndpoint()
        withS3Gateway { server ->
            server.client(endpoint = unreachable).useS3Client { client ->
                val e = assertFailsWith<S3Exception.Transient> { client.listObjects() }
                assertTrue(e.retryable)
            }
        }
    }

    @Test
    fun `a skewed device clock is corrected`() = runTest {
        withS3Gateway { server ->
            val skewed = object : Clock {
                override fun now(): Instant = Clock.System.now() + 30.minutes
            }
            server.client(clock = skewed).useS3Client { client ->
                client.putObject("clock.bin", byteArrayOf(1))
                assertNotNull(client.headObject("clock.bin"))
                assertEquals(listOf("clock.bin"), client.listObjects().objects.map { it.key })
            }
        }
    }

    @Test
    fun `virtual-hosted addressing`() = runTest {
        withS3Gateway { server ->
            // Resolve the virtual domain and its bucket subdomains to the
            // local gateway without relying on the host resolver.
            val okHttp = OkHttpClient.Builder()
                .dns { hostname ->
                    if (hostname.endsWith(VersityGwServer.VIRTUAL_DOMAIN)) {
                        listOf(InetAddress.getLoopbackAddress())
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
                .build()
            val httpClient = HttpClient(OkHttp) {
                followRedirects = false
                engine {
                    preconfigured = okHttp
                }
            }
            server.client(
                httpClient = httpClient,
                endpoint = "http://${VersityGwServer.VIRTUAL_DOMAIN.uppercase()}:${server.port}",
                pathStyle = false,
            ).useS3Client { client ->
                client.putObject("dir/virtual host.bin", byteArrayOf(7))
                val read = client.getObject("dir/virtual host.bin").use { it.readByteArray() }
                assertContentEquals(byteArrayOf(7), read)
                assertEquals(
                    listOf("dir/virtual host.bin"),
                    client.listObjects(prefix = "dir/").objects.map { it.key },
                )
            }
        }
    }

}
