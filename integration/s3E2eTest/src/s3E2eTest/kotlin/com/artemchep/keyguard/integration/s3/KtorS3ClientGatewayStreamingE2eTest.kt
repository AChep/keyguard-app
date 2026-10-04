package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorS3ClientGatewayStreamingE2eTest {
    @Test
    fun `empty objects and streamed chunks round trip on both engines`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    for (size in listOf(0, 1, 65537)) {
                        val payload = Random(size).nextBytes(size)
                        val key = "$name/chunks-$size"
                        var passes = 0
                        client.putObject(key, size.toLong()) { sink ->
                            passes++
                            for (offset in payload.indices step 137) {
                                sink.write(payload, offset, minOf(offset + 137, payload.size))
                            }
                        }
                        assertEquals(2, passes)
                        assertEquals(size.toLong(), client.headObject(key)?.size)
                        assertContentEquals(payload, client.getObject(key).use { it.readByteArray() })
                    }
                }
            }
        }
    }

    @Test
    fun `closing a partial download releases it for subsequent requests`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client(httpClient = httpClient()).useS3Client { client ->
                    val key = "$name/partial"
                    val payload = Random(19).nextBytes(1024 * 1024)
                    client.putObject(key, payload)
                    client.getObject(key).use { assertEquals(payload[0], it.readByte()) }
                    assertContentEquals(payload, client.getObject(key).use { it.readByteArray() })
                    assertEquals(payload.size.toLong(), client.headObject(key)?.size)
                }
            }
        }
    }

    @Test
    fun `server rejects concurrent changes made right before a conditional write`() = runTest {
        withS3Gateway { server ->
            s3HttpEngines.forEach { (name, httpClient) ->
                server.client().useS3Client { competitor ->
                    for (replace in listOf(false, true)) {
                        val key = "$name/race-$replace"
                        val initial = if (replace) competitor.putObject(key, byteArrayOf(1)) else null
                        val precondition = initial?.etag?.let { S3WritePrecondition.IfMatch(it) }
                            ?: S3WritePrecondition.IfNoneMatch
                        var puts = 0
                        val race = createClientPlugin("ConcurrentS3Write") {
                            onRequest { request, _ ->
                                if (request.method == HttpMethod.Put) {
                                    puts++
                                    // This hook runs before the client's signed PUT.
                                    competitor.putObject(key, byteArrayOf(2))
                                }
                            }
                        }
                        val http = httpClient()
                        val racingHttp = http.config { install(race) }
                        try {
                            server.client(httpClient = racingHttp).useS3Client { client ->
                                val error = assertFailsWith<S3Exception> {
                                    client.putObject(key, byteArrayOf(3), precondition)
                                }
                                val expected = if (replace) {
                                    S3Exception.PreconditionFailed::class
                                } else {
                                    S3Exception.AlreadyExists::class
                                }
                                assertEquals(expected, error::class, "$name replace=$replace")
                                // Prove rejection happened at the server.
                                assertEquals(412, error.statusCode)
                                assertEquals(1, puts)
                            }
                        } finally {
                            racingHttp.close()
                            http.close()
                        }
                        assertContentEquals(byteArrayOf(2), competitor.getObject(key).use { it.readByteArray() })
                    }
                }
            }
        }
    }

}
