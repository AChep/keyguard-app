package com.artemchep.keyguard.util.s3

import com.artemchep.keyguard.util.s3.internal.mapS3TransportException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class S3StreamingTest {
    @Test
    fun `get fails promptly after the HTTP client closes or is cancelled`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                for (cancelHttpScope in listOf(false, true)) {
                    val engine = MockEngine { error("A terminated client reached the network") }
                    val http = HttpClient(engine) { followRedirects = false }
                    val client = KtorS3Client(http, testS3Config(), closeHttpClient = true)
                    try {
                        val httpJob = http.coroutineContext[Job]!!
                        if (cancelHttpScope) {
                            httpJob.cancelAndJoin()
                        } else {
                            client.close()
                            httpJob.join()
                        }
                        val error = assertFailsWith<S3Exception.Transient> { client.getObject("key") }
                        assertEquals(S3Operation.Get, error.operation)
                        assertEquals("key", error.key)
                    } finally {
                        client.close()
                        engine.close()
                    }
                }
            }
        }
    }

    @Test
    fun `HTTP client cancellation before headers is transient for an active caller`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                val entered = CompletableDeferred<Unit>()
                val engine = MockEngine {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val http = HttpClient(engine) { followRedirects = false }
                val client = KtorS3Client(http, testS3Config(), closeHttpClient = true)
                try {
                    val cancelling = launch {
                        entered.await()
                        http.coroutineContext[Job]!!.cancel()
                    }
                    val error = assertFailsWith<S3Exception.Transient> { client.getObject("key") }
                    assertEquals(S3Operation.Get, error.operation)
                    assertEquals("key", error.key)
                    cancelling.join()
                } finally {
                    client.close()
                    engine.close()
                }
            }
        }
    }

    @Test
    fun `get returns before the body arrives and supports incremental reads`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                val channel = ByteChannel(autoFlush = true)
                withS3Client(handler = {
                    respond(channel, headers = headersOf(HttpHeaders.ContentLength, "6"))
                }) { client ->
                    client.getObject("key").use { source ->
                        channel.writeFully("abc".encodeToByteArray())
                        assertEquals("abc", source.readByteArray(3).decodeToString())
                        channel.writeFully("def".encodeToByteArray())
                        channel.close()
                        assertEquals("def", source.readByteArray().decodeToString())
                    }
                }
            }
        }
    }

    @Test
    fun `closing a partially read source cancels the body and allows another request`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully("abc".encodeToByteArray())
        var requests = 0
        withS3Client(handler = {
            requests++
            if (requests == 1) respond(channel) else respond("next")
        }) { client ->
            client.getObject("key").use { assertEquals('a'.code.toByte(), it.readByte()) }
            assertTrue(channel.isClosedForRead)
            assertEquals("next", client.getObject("other").use { it.readByteArray().decodeToString() })
        }
    }

    @Test
    fun `invalid response headers and ignored ranges release an unfinished body`() = runTest {
        for (range in listOf(null, S3ByteRange(1, 1))) {
            val channel = ByteChannel(autoFlush = true)
            withS3Client(handler = {
                respond(channel, headers = headersOf(HttpHeaders.ContentLength, "-1"))
            }) { client ->
                assertFailsWith<S3Exception> { client.getObject("key", range) }
                assertTrue(channel.isClosedForRead)
            }
        }
    }

    @Test
    fun `consumer cancellation interrupts a blocked body read`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                val channel = ByteChannel(autoFlush = true)
                val ready = CompletableDeferred<Unit>()
                withS3Client(handler = { respond(channel) }) { client ->
                    val consumer = launch {
                        client.getObject("key").use {
                            ready.complete(Unit)
                            it.readByteArray()
                        }
                    }
                    ready.await()
                    consumer.cancelAndJoin()
                    assertTrue(consumer.isCancelled)
                    assertTrue(channel.isClosedForRead)
                }
            }
        }
    }

    @Test
    fun `consumer cancellation before headers tears down the request`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                val entered = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                withS3Client(handler = {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        finished.complete(Unit)
                    }
                }) { client ->
                    val consumer = launch { client.getObject("key").close() }
                    entered.await()
                    consumer.cancelAndJoin()
                    finished.await()
                }
            }
        }
    }

    @Test
    fun `body failures including transport cancellations become transient for an active caller`() = runTest {
        val failures = listOf(
            IOException("disconnected"),
            CancellationException("engine stopped"),
            IllegalStateException("engine stopped", CancellationException("request stopped")),
        )
        for (failure in failures) {
            val channel = ByteChannel(autoFlush = true)
            channel.close(failure)
            withS3Client(handler = { respond(channel) }) { client ->
                val error = assertFailsWith<S3Exception.Transient> {
                    client.getObject("key").use { it.readByteArray() }
                }
                assertEquals(S3Operation.Get, error.operation)
                assertEquals("key", error.key)
                assertTrue(error.retryable)
            }
        }
    }

    @Test
    fun `successful XML looking objects are not interpreted as errors`() = runTest {
        val payload = "<Error><Code>AccessDenied</Code></Error>"
        withS3Client(handler = { respond(payload, HttpStatusCode.OK) }) { client ->
            assertEquals(payload, client.getObject("key").use { it.readByteArray().decodeToString() })
        }
    }

    @Test
    fun `transport mapper preserves typed errors and caller cancellation`() {
        val typed = S3Exception.NotFound(S3Operation.Get, "key")
        assertSame(typed, assertFailsWith<S3Exception.NotFound> { mapFailure(typed) })
        val cancelled = CancellationException("caller stopped")
        assertSame(cancelled, assertFailsWith<CancellationException> { mapFailure(cancelled) })
        val failure = IOException("transport failed")
        val mapped = assertFailsWith<S3Exception.Transient> { mapFailure(failure) }
        assertSame(failure, mapped.cause)
        assertEquals(S3Operation.Get, mapped.operation)
    }

    private fun mapFailure(failure: Throwable): Nothing =
        mapS3TransportException(S3Operation.Get, "key") { throw failure }
}
