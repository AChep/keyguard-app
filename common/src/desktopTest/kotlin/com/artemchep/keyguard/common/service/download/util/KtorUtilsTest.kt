package com.artemchep.keyguard.common.service.download.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TEST_BYTE_PATTERN_MODULUS = 251
private const val CHUNK_SIZE = 16 * 1024
private const val SIZE_LIMIT = 128L * 1024
private const val OVERSIZED_TOTAL = 4L * 1024 * 1024

private class TooLargeException : RuntimeException("Too large.")

@Suppress("FunctionNaming")
class KtorUtilsTest {
    @Test
    fun `download returns full body and reports progress`() = runTest {
        val bytes = ByteArray(SIZE_LIMIT.toInt()) { index ->
            (index % TEST_BYTE_PATTERN_MODULUS).toByte()
        }
        val httpClient = HttpClient(
            MockEngine { request ->
                assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
                respond(
                    content = ByteReadChannel(bytes),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, bytes.size.toString()),
                )
            },
        )
        var lastDownloaded = 0L
        var lastTotal: Long? = null

        val result = httpClient.downloadToByteArray(
            url = "https://example.com/attachment",
            bufferSize = CHUNK_SIZE,
            validateSize = ::validateSize,
            onProgress = { downloaded, total ->
                lastDownloaded = downloaded
                lastTotal = total
            },
        )

        assertContentEquals(bytes, result)
        assertEquals(bytes.size.toLong(), lastDownloaded)
        assertEquals(bytes.size.toLong(), lastTotal)
    }

    @Test
    fun `download rejects oversized body before it is fully produced`() = runTest {
        assertRejectsMidStream(headers = headersOf())
    }

    @Test
    fun `download rejects oversized body with lying content length before it is fully produced`() = runTest {
        assertRejectsMidStream(
            headers = headersOf(HttpHeaders.ContentLength, CHUNK_SIZE.toString()),
        )
    }

    private suspend fun CoroutineScope.assertRejectsMidStream(
        headers: Headers,
    ) {
        val written = AtomicLong()
        val channel = ByteChannel()
        launch(Dispatchers.Default) {
            val chunk = ByteArray(CHUNK_SIZE)
            try {
                while (written.get() < OVERSIZED_TOTAL) {
                    channel.writeFully(chunk)
                    channel.flush()
                    written.addAndGet(chunk.size.toLong())
                }
                channel.flushAndClose()
            } catch (_: Throwable) {
                // The reader cancelled the body; stop producing.
            }
        }
        val httpClient = HttpClient(
            MockEngine {
                respond(
                    content = channel,
                    status = HttpStatusCode.OK,
                    headers = headers,
                )
            },
        )

        assertFailsWith<TooLargeException> {
            httpClient.downloadToByteArray(
                url = "https://example.com/attachment",
                bufferSize = CHUNK_SIZE,
                validateSize = ::validateSize,
            )
        }
        val writtenAtFailure = written.get()
        assertTrue(
            writtenAtFailure < OVERSIZED_TOTAL,
            "Expected the body to be rejected mid-stream, but $writtenAtFailure of $OVERSIZED_TOTAL bytes were produced.",
        )
    }

    private fun validateSize(size: Long) {
        if (size > SIZE_LIMIT) {
            throw TooLargeException()
        }
    }
}
