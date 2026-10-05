package com.artemchep.keyguard.util.s3.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class S3ResponseTextTest {
    @Test
    fun `small text limits cancel unfinished responses before leaving the response scope`() = runTest {
        for (prefix in listOf("", "<ListBucketResult>")) {
            val text = prefix + "x".repeat(64)
            val channel = ByteChannel(autoFlush = true)
            channel.writeFully(text.encodeToByteArray())
            val http = HttpClient(MockEngine { respond(channel) }) {
                // Observe the reader's channel directly, without Ktor's
                // asynchronous forwarding channel for bodyAsChannel().
                useDefaultTransformers = false
            }
            try {
                http.prepareGet("https://example.com").execute { response ->
                    assertEquals(text.take(32), response.readS3ResponseText(32))
                    // Ktor also cancels the body when execute returns. Check that
                    // the bounded reader itself has already released it.
                    assertTrue(channel.isClosedForRead)
                }
            } finally {
                channel.cancel(null)
                http.close()
            }
        }
    }

    @Test
    fun `caller timeout propagates and cancels an unfinished response`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val http = HttpClient(MockEngine { respond(channel) }) {
            useDefaultTransformers = false
        }
        try {
            http.prepareGet("https://example.com").execute { response ->
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(1.seconds) { response.readS3ResponseText(32) }
                }
                assertTrue(channel.isClosedForRead)
            }
        } finally {
            channel.cancel(null)
            http.close()
        }
    }

    @Test
    fun `UTF8 code points split across transport chunks survive decoding`() = runTest {
        // The first multi-byte code point straddles the reader's 8192-byte chunk.
        val text = "x".repeat(8191) + "é日本😀\uFFFDz"
        val channel = ByteChannel(autoFlush = true)
        val http = HttpClient(MockEngine { respond(channel) })
        val writer = launch {
            for (byte in text.encodeToByteArray()) channel.writeFully(byteArrayOf(byte))
            channel.close()
        }
        try {
            val result = http.prepareGet("https://example.com").execute { it.readS3ResponseText(text.length + 1) }
            assertEquals(text, result)
            writer.join()
        } finally {
            writer.cancel()
            http.close()
        }
    }

    @Test
    fun `text limit counts characters rather than UTF8 bytes`() = runTest {
        for (text in listOf("", "abc", "é".repeat(8193), "😀".repeat(4097))) {
            for (limit in listOf(0, 1, 2, 3, 8192, 9000)) {
                val http = HttpClient(MockEngine { respond(text) })
                try {
                    assertEquals(
                        text.take(limit),
                        http.prepareGet("https://example.com").execute { it.readS3ResponseText(limit) },
                    )
                } finally {
                    http.close()
                }
            }
        }
    }

    @Test
    fun `malformed or incomplete UTF8 fails instead of changing response text`() = runTest {
        val invalidSequences = listOf(
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),
        )
        for (bytes in invalidSequences) {
            for (prefixLength in listOf(0, 8191)) {
                val body = ByteArray(prefixLength) { 'a'.code.toByte() } + bytes
                val http = HttpClient(MockEngine { respond(body) })
                try {
                    assertFailsWith<IllegalArgumentException> {
                        http.prepareGet("https://example.com").execute { it.readS3ResponseText(body.size + 1) }
                    }
                } finally {
                    http.close()
                }
            }
        }
    }
}
