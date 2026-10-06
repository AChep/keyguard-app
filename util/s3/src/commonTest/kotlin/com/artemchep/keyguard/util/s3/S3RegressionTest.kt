package com.artemchep.keyguard.util.s3

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class S3RegressionTest {
    @Test
    fun `concurrent requests recover using their own signing time`() = runTest {
        // GET uses a streaming request; HEAD uses the regular request path.
        for (streaming in listOf(false, true)) {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirstResponse = CompletableDeferred<Unit>()
            val attempts = mutableListOf<Pair<String, String>>()
            withS3Client(handler = { request ->
                val key = request.url.encodedPath.substringAfterLast('/')
                val date = assertNotNull(request.headers["x-amz-date"])
                attempts += key to date
                if (date == "20240229T235958Z") {
                    if (key == "a") {
                        firstStarted.complete(Unit)
                        releaseFirstResponse.await()
                    }
                    respond("", HttpStatusCode.Forbidden, headersOf(HttpHeaders.Date, "Fri, 01 Mar 2024 00:59:58 GMT"))
                } else {
                    respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "2"))
                }
            }) { client ->
                suspend fun read(key: String) {
                    if (streaming) {
                        assertEquals("ok", client.getObject(key).use { it.readByteArray().decodeToString() })
                    } else {
                        assertEquals(2L, assertNotNull(client.headObject(key)).size)
                    }
                }

                val first = async { read("a") }
                firstStarted.await()
                read("b")
                releaseFirstResponse.complete(Unit)
                first.await()
                assertEquals(
                    listOf(
                        "a" to "20240229T235958Z",
                        "b" to "20240229T235958Z",
                        "b" to "20240301T005958Z",
                        "a" to "20240301T005958Z",
                    ),
                    attempts,
                )
            }
        }
    }

    @Test
    fun `redirects are surfaced without sending the signed request to another location`() = runTest {
        for (operation in S3Operation.entries) {
            var requests = 0
            withS3Client(handler = {
                requests++
                if (requests == 1) {
                    respond("", HttpStatusCode.TemporaryRedirect, headersOf(
                        HttpHeaders.Location to listOf("https://other.example.com/bucket/key"),
                        "x-amz-bucket-region" to listOf("eu-west-1"),
                    ))
                } else {
                    respond("<ListBucketResult/>", HttpStatusCode.OK)
                }
            }) { client ->
                val error = assertFailsWith<S3Exception.WrongRegion>(operation.name) {
                    when (operation) {
                        S3Operation.Head -> client.headObject("key")
                        S3Operation.Get -> client.getObject("key").close()
                        S3Operation.Put -> client.putObject("key", byteArrayOf())
                        S3Operation.List -> client.listObjects()
                        S3Operation.Delete -> client.deleteObject("key")
                    }
                }
                assertEquals("eu-west-1", error.expectedRegion)
                assertEquals(1, requests)
            }
        }
    }

    @Test
    fun `successful upload does not require permission to read its metadata`() = runTest {
        var puts = 0
        var heads = 0
        withS3Client(handler = { request ->
            if (request.method == HttpMethod.Put) {
                puts++
                respond("", HttpStatusCode.OK)
            } else {
                heads++
                respond("", HttpStatusCode.Forbidden, headersOf(HttpHeaders.Date, "Fri, 01 Mar 2024 0$heads:00:00 GMT"))
            }
        }) { client ->
            assertNull(client.putObject("key", byteArrayOf(1)).etag)
            assertEquals(1, puts)
            assertEquals(0, heads)
        }
    }

    @Test
    fun `unfinished or mismatched listing XML is a protocol failure`() = runTest {
        val documents = listOf(
            "<ListBucketResult>",
            "<ListBucketResult><Contents><Key>key</Key></Contents>",
            "<ListBucketResult><Contents><Key>key</Wrong></Contents></ListBucketResult>",
            "<ListBucketResult/></ListBucketResult>",
            "garbage<ListBucketResult/>",
        )
        for (document in documents) {
            withS3Client(handler = { respond(document) }) { client ->
                assertFailsWith<S3Exception.Protocol>(document) { client.listObjects() }
            }
        }
    }

    @Test
    fun `malformed listing syntax is rejected instead of returning changed keys`() = runTest {
        val contents = listOf(
            "<Key>a<!--bad--comment-->b</Key>",
            "< Key>a</ Key>",
        ).map { fragment ->
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                "<Contents>$fragment</Contents></ListBucketResult>"
        }
        val documents = contents +
            "<ListBucketResult a='1'b='2'><IsTruncated>false</IsTruncated></ListBucketResult>"
        for (document in documents) {
            withS3Client(handler = { respond(document) }) { client ->
                assertFailsWith<S3Exception.Protocol>(document) { client.listObjects() }
            }
        }
    }
}
