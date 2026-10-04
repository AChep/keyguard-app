package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.util.s3.KtorS3Client
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Credentials
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KtorS3ClientMutationE2eTest {
    @Test
    fun `unsupported preconditions never become unconditional writes`() = runTest {
        for ((name, engine) in s3HttpEngines) {
            for (status in listOf(400, 501)) {
                S3HttpFixture { request, socket ->
                    request.verifySignature()
                    socket.respond(status, body = "<Error><Code>NotImplemented</Code></Error>")
                }.use { server ->
                    KtorS3Client(engine(), config(server.endpoint), closeHttpClient = true).useS3Client { client ->
                        val conditions = listOf(
                            S3WritePrecondition.IfNoneMatch,
                            S3WritePrecondition.IfMatch("\"v1\""),
                            S3WritePrecondition.IfMatch("W/\"v1\""),
                        )
                        for (condition in conditions) {
                            assertFailsWith<S3Exception.Protocol>(name) {
                                client.putObject("key", byteArrayOf(1), condition)
                            }
                        }
                    }
                    assertEquals(listOf("PUT", "PUT", "PUT"), server.requests.map { it.method }, name)
                    assertEquals("*", server.requests[0].headers["if-none-match"])
                    assertEquals("\"v1\"", server.requests[1].headers["if-match"])
                    assertEquals("W/\"v1\"", server.requests[2].headers["if-match"])
                }
            }
        }
    }

    @Test
    fun `successful uploads do not recover missing ETags using HEAD`() = runTest {
        for ((name, engine) in s3HttpEngines) {
            for (headStatus in listOf(200, 403)) {
                S3HttpFixture { request, socket ->
                    if (request.method == "HEAD") {
                        socket.respond(headStatus, mapOf("ETag" to "\"concurrent-version\""))
                    } else {
                        socket.respond()
                    }
                }.use { server ->
                    KtorS3Client(engine(), config(server.endpoint), closeHttpClient = true).useS3Client { client ->
                        assertNull(client.putObject("key", byteArrayOf(1)).etag)
                    }
                    assertEquals(listOf("PUT"), server.requests.map { it.method }, name)
                    assertContentEquals(byteArrayOf(1), server.requests.single().body)
                }
            }
        }
    }

    @Test
    fun `slash keys and endpoint routes preserve their wire paths and signatures`() = runTest {
        val keys = listOf("/key", "dir/", "a//b", "//", "a/%2E/b")
        for ((name, engine) in s3HttpEngines) {
            S3HttpFixture { request, socket ->
                request.verifySignature()
                when (request.method) {
                    "PUT", "HEAD" -> socket.respond(headers = mapOf("ETag" to "\"v1\""))
                    "GET" -> socket.respond(body = "value")
                    else -> socket.respond(204)
                }
            }.use { server ->
                val endpoint = server.endpoint.replace("127.0.0.1", "LOCALHOST") + "/base//route/"
                KtorS3Client(engine(), config(endpoint), closeHttpClient = true).useS3Client { client ->
                    for (key in keys) {
                        client.putObject(key, byteArrayOf(1))
                        client.headObject(key)
                        assertEquals("value", client.getObject(key).use { it.readByteArray().decodeToString() })
                        client.deleteObject(key)
                    }
                }
                val paths = keys.flatMap { key -> List(4) { "/base//route/bucket/${key.replace("%", "%25")}" } }
                assertEquals(paths, server.requests.map { it.target }, name)
                assertEquals(setOf("localhost:${server.endpoint.substringAfterLast(':')}"),
                    server.requests.map { it.headers["host"] }.toSet())
            }
        }
    }

    @Test
    fun `unrepresentable keys fail before any network access`() = runTest {
        for ((name, engine) in s3HttpEngines) {
            S3HttpFixture { _, socket -> socket.respond() }.use { server ->
                KtorS3Client(engine(), config(server.endpoint), closeHttpClient = true).useS3Client { client ->
                    for (key in listOf("\uD800", "\uDC00", "a/./b", "a/../b")) {
                        assertFailsWith<IllegalArgumentException>(name) { client.headObject(key) }
                        assertFailsWith<IllegalArgumentException>(name) { client.getObject(key) }
                        assertFailsWith<IllegalArgumentException>(name) { client.deleteObject(key) }
                        assertFailsWith<IllegalArgumentException>(name) {
                            client.putObject(key, 0) { error("Invalid key invoked the body") }
                        }
                    }
                }
                assertEquals(0, server.requests.size, name)
            }
        }
    }

    private fun config(endpoint: String) = S3ClientConfig(
        endpoint = endpoint,
        bucket = "bucket",
        credentials = S3Credentials("test-access", "test-secret"),
    )
}
